package vip.mate.team.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.provider.RuleDecisionProvider;
import vip.mate.decision.record.JdbcDecisionRecordStore;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.agent.AgentService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.conversation.ConversationService;
import cn.hutool.json.JSONUtil;
import vip.mate.decision.provider.DecisionResult;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.Arrays;
import vip.mate.team.repository.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerDecisionTransactionTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    JdbcDecisionRecordStore records;
    DecisionService core;
    WorkerDecisionAdapter adapter;
    TeamTaskService tasks;
    DecisionProperties properties;
    TeamService teams;
    boolean rejectProvider;
    AtomicInteger providerCalls = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:worker_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V172__agent_team_foundation.sql"),
                new ClassPathResource("db/migration/h2/V174__team_task_event_timeline.sql"),
                new ClassPathResource("db/migration/h2/V181__team_run_foundation.sql"),
                new ClassPathResource("db/migration/h2/V203__decision_record.sql")).execute(source);
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration();
        config.addMapper(TeamTaskMapper.class); config.addMapper(TeamTaskEventMapper.class); config.addMapper(TeamTaskCommentMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        teams = mock(TeamService.class);
        properties = new DecisionProperties(); properties.setMode(DecisionMode.ACTIVE);
        records = spy(new JdbcDecisionRecordStore(jdbc, manager, properties));
        core = new DecisionService(properties, List.of(new RuleDecisionProvider() {
            @Override public DecisionResult decide(DecisionRequest request) {
                providerCalls.incrementAndGet();
                return rejectProvider ? DecisionResult.proposed(new DecisionValue.BooleanValue(false), 1, "test-v1")
                        : super.decide(request);
            }
        }), records, new SimpleMeterRegistry());
        adapter = new WorkerDecisionAdapter(core, properties, teams);
        var target = new TeamTaskService(session.getMapper(TeamTaskMapper.class), session.getMapper(TeamTaskCommentMapper.class),
                session.getMapper(TeamTaskEventMapper.class), teams, mock(TeamRunProjectionScheduler.class), mock(TeamRunService.class));
        target.setDecisionAdapter(adapter);
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        tasks = (TeamTaskService) proxy.getProxy();
        jdbc.update("INSERT INTO mate_team_task(id,team_id,task_number,subject,status,assignee_agent_id,owner_agent_id,dispatch_count,conversation_id,require_approval) VALUES(1,2,1,'Analyze results','in_progress',3,3,1,'worker-conversation',FALSE)");
    }
    @AfterEach void close() { core.close(); }

    @ParameterizedTest @ValueSource(strings = {"complete", "retry", "fail", "checkpoint"})
    void auditFailureRollsBackBusinessTransition(String action) {
        var task = tasks.getTask(1L); var snapshot = WorkerDecisionAdapter.Snapshot.capture(task);
        var ticket = adapter.judge(task, "VALID", "analysis done").ticket();
        doThrow(new IllegalStateException("audit unavailable")).when(records).outcome(any(), any(), any());
        assertThrows(DecisionRecordingException.class, () -> {
            switch (action) {
                case "complete" -> tasks.completeTask(1L, null, "done", ticket, snapshot);
                case "retry" -> tasks.settleUnusableResult(1L, "member produced no result", true, ticket, snapshot);
                case "fail" -> tasks.failTask(1L, "rejected", ticket, snapshot);
                default -> tasks.parkWorkerCheckpoint(1L, 10, "waiting", ticket, snapshot);
            }
        });
        assertEquals(TeamTaskStatus.IN_PROGRESS, tasks.getTask(1L).getStatus());
        assertNull(tasks.getTask(1L).getResult());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_team_task_event", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
    }

    @Test void freshApprovalAuthorityAndActualOutcomeAreCommittedTogether() {
        var task = tasks.getTask(1L); var snapshot = WorkerDecisionAdapter.Snapshot.capture(task);
        var ticket = adapter.judge(task, "VALID", "analysis done").ticket();
        jdbc.update("UPDATE mate_team_task SET require_approval=TRUE WHERE id=1");
        assertTrue(tasks.completeTask(1L, null, "done", ticket, snapshot).applied());
        assertEquals(TeamTaskStatus.IN_REVIEW, tasks.getTask(1L).getStatus());
        assertEquals("IN_REVIEW", jdbc.queryForObject("SELECT actual_value FROM mate_decision_outcome", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
    }

    @Test void concurrentReassignmentWhileWaitingForLockRejectsOldReply() throws Exception {
        var task = tasks.getTask(1L); var snapshot = WorkerDecisionAdapter.Snapshot.capture(task);
        var ticket = adapter.judge(task, "VALID", "analysis done").ticket();
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<TeamTaskService.Completion> pending = new TransactionTemplate(manager).execute(status -> {
                jdbc.queryForList("SELECT id FROM mate_team_task WHERE id=1 FOR UPDATE");
                var future = executor.submit(() -> { started.countDown(); return tasks.completeTask(1L, null, "old reply", ticket, snapshot); });
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
                } catch (InterruptedException error) { throw new RuntimeException(error); }
                jdbc.update("UPDATE mate_team_task SET owner_agent_id=4, dispatch_count=2, conversation_id='replacement' WHERE id=1");
                return future;
            });
            assertFalse(pending.get(5, TimeUnit.SECONDS).applied());
        }
        assertEquals(4L, tasks.getTask(1L).getOwnerAgentId());
        assertNull(tasks.getTask(1L).getResult());
        assertEquals("NOT_APPLIED", jdbc.queryForObject("SELECT outcome FROM mate_decision_outcome", String.class));
        assertFalse(tasks.addWorkerDeliverable(snapshot, 3L, "old artifact", "/api/v1/files/generated/abc"));
        assertNull(tasks.getTask(1L).getMetadata());
    }

    @Test void offPreservesLegacySettlementAcrossGuardsAndObservationFailures() {
        rejectProvider = true;
        for (int fixture = 0; fixture < 13; fixture++) {
            properties.setMode(DecisionMode.OFF);
            List<Object> expected = settleFixture(fixture, false);
            for (DecisionMode mode : List.of(DecisionMode.OFF)) {
                for (boolean failingStore : List.of(false, true)) {
                    properties.setMode(mode);
                    if (failingStore) doThrow(new IllegalStateException("unavailable")).when(records).insert(any());
                    else doCallRealMethod().when(records).insert(any());
                    for (boolean failingLookup : List.of(false, true)) {
                        if (failingLookup) doThrow(new IllegalStateException("metadata unavailable")).when(teams).getTeam(any());
                        else doReturn(null).when(teams).getTeam(any());
                        assertEquals(expected, settleFixture(fixture, true),
                                "fixture=" + fixture + ",mode=" + mode + ",store=" + failingStore + ",lookup=" + failingLookup);
                    }
                }
            }
        }
    }

    private List<Object> settleFixture(int fixture, boolean enabled) {
        jdbc.update("DELETE FROM mate_team_task_comment"); jdbc.update("DELETE FROM mate_team_task_event");
        jdbc.update("UPDATE mate_team_task SET status='in_progress',owner_agent_id=3,dispatch_count=1,conversation_id='worker-conversation',subject='Analyze results',description=NULL,require_approval=FALSE,progress_percent=NULL,progress_step=NULL,result=NULL,reason=NULL,metadata=NULL WHERE id=1");
        String reply = "analysis done";
        switch (fixture) {
            case 1 -> reply = "";
            case 2 -> reply = "Failed to generate a response, please retry.";
            case 3 -> { reply = ""; jdbc.update("UPDATE mate_team_task SET dispatch_count=2 WHERE id=1"); }
            case 4 -> jdbc.update("UPDATE mate_team_task SET metadata=? WHERE id=1", "{\"deliverableRequired\":true}");
            case 5 -> reply = "Could you provide more information?";
            case 6 -> { reply = "[report](/api/v1/files/generated/12345678-abcd-1234-abcd-123456789abc)"; jdbc.update("UPDATE mate_team_task SET metadata=? WHERE id=1", "{\"deliverableRequired\":true}"); }
            case 7, 8 -> {
                jdbc.update("UPDATE mate_team_task SET subject='checkpoint R001-R300' WHERE id=1");
                if (fixture == 8) jdbc.update("INSERT INTO mate_team_task_comment(id,task_id,team_id,content) VALUES(9,1,2,'[checkpoint:R300] acknowledged')");
            }
            case 9 -> jdbc.update("UPDATE mate_team_task SET require_approval=TRUE WHERE id=1");
            case 10 -> jdbc.update("UPDATE mate_team_task SET status='completed', result='explicit result' WHERE id=1");
            case 11 -> { reply = "Could you provide more information?"; jdbc.update("UPDATE mate_team_task SET dispatch_count=3 WHERE id=1"); }
            case 12 -> { reply = "Failed to generate a response, please retry."; jdbc.update("UPDATE mate_team_task SET dispatch_count=2 WHERE id=1"); }
        }
        var events = new ArrayList<String>();
        var announcements = new ArrayList<String>();
        var channel = mock(TeamEventChannel.class);
        doAnswer(call -> { events.add(call.getArgument(1)); return null; }).when(channel).publishTaskEvent(any(), anyString(), any());
        var announce = mock(TeamAnnounceService.class);
        doAnswer(call -> { announcements.add("settled"); return null; }).when(announce).announceTaskSettled(any());
        var dispatch = new TeamDispatchService(teams, tasks, mock(AgentService.class), mock(ConversationService.class),
                mock(ChatStreamTracker.class), announce, channel, mock(ApprovalWorkflowService.class));
        if (enabled) dispatch.setDecisionAdapter(adapter);
        dispatch.settleOutcome(tasks.getTask(1L), reply);
        var result = tasks.getTask(1L);
        String metadata = result.getMetadata();
        if (fixture == 6 && metadata != null) {
            var json = JSONUtil.parseObj(metadata);
            json.getJSONArray("deliverables").getJSONObject(0).remove("time"); metadata = json.toString();
        }
        return Arrays.asList(result.getStatus(), result.getResult(), result.getReason(), result.getOwnerAgentId(),
                result.getDispatchCount(), result.getRequireApproval(), result.getProgressPercent(), result.getProgressStep(),
                metadata, events, announcements,
                jdbc.queryForList("SELECT event_type FROM mate_team_task_event ORDER BY create_time,id", String.class));
    }

    private static String structured(String result) {
        return JSONUtil.toJsonStr(java.util.Map.of("status", "COMPLETED", "result", result, "evidence", "measured"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = DecisionMode.class, names = {"SHADOW", "ACTIVE"})
    void structuredBlockNeverCompletesOrEntersReview(DecisionMode mode) throws Exception {
        properties.setMode(mode);
        jdbc.update("UPDATE mate_team_task SET require_approval=TRUE WHERE id=1");
        var observed = new CountDownLatch(1);
        doAnswer(call -> { call.callRealMethod(); observed.countDown(); return null; })
                .when(records).outcome(any(), any(), any());
        dispatch(mock(AgentService.class)).settleOutcome(tasks.getTask(1L),
                "{\"status\":\"BLOCKED\",\"result\":\"\",\"evidence\":\"missing total\"}");
        assertTrue(observed.await(5, TimeUnit.SECONDS));
        assertEquals(TeamTaskStatus.FAILED, tasks.getTask(1L).getStatus());
        assertTrue(tasks.getTask(1L).getReason().contains("missing total"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT actual_value FROM mate_decision_outcome", String.class));
        assertEquals(0, providerCalls.get());
    }

    @Test void healthyShadowActuallyEvaluatesAndObservesCommittedBaseline() throws Exception {
        properties.setMode(DecisionMode.SHADOW); rejectProvider = true;
        var observed = new CountDownLatch(1);
        doAnswer(call -> { call.callRealMethod(); observed.countDown(); return null; })
                .when(records).outcome(any(), any(), any());
        dispatch(mock(AgentService.class)).settleOutcome(tasks.getTask(1L), structured("analysis done"));
        assertTrue(observed.await(5, TimeUnit.SECONDS));
        assertEquals(1, providerCalls.get());
        assertEquals(TeamTaskStatus.COMPLETED, tasks.getTask(1L).getStatus());
        assertEquals("false", jdbc.queryForObject("SELECT proposed_value FROM mate_decision_record", String.class));
        assertEquals("OBSERVED", jdbc.queryForObject("SELECT outcome FROM mate_decision_outcome", String.class));
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT actual_value FROM mate_decision_outcome", String.class));
    }

    @Test void successfulTransitionAndOutcomeBothDisappearOnOuterRollback() {
        var task = tasks.getTask(1L); var ticket = adapter.judge(task, "VALID", "done").ticket();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertTrue(tasks.completeTask(1L, null, "done", ticket, WorkerDecisionAdapter.Snapshot.capture(task)).applied());
            status.setRollbackOnly();
        });
        assertEquals(TeamTaskStatus.IN_PROGRESS, tasks.getTask(1L).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
    }

    @Test void activeSemanticRejectionCommitsFailureWithoutRetry() {
        rejectProvider = true;
        var dispatch = dispatch(mock(AgentService.class));
        dispatch.settleOutcome(tasks.getTask(1L), structured("analysis done"));
        assertEquals(TeamTaskStatus.FAILED, tasks.getTask(1L).getStatus());
        assertEquals(1, tasks.getTask(1L).getDispatchCount());
        assertEquals("FAILED", jdbc.queryForObject("SELECT actual_value FROM mate_decision_outcome", String.class));
        assertEquals(List.of("failed"), jdbc.queryForList("SELECT event_type FROM mate_team_task_event", String.class));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void dispatchedRunUsesOriginalIdentityWithItsNewChildConversation(boolean reassigned) {
        var agent = mock(AgentService.class);
        when(agent.chatWithUsage(eq(3L), anyString(), anyString())).thenAnswer(call -> {
            if (reassigned) jdbc.update("UPDATE mate_team_task SET owner_agent_id=4,dispatch_count=2,conversation_id='new-run' WHERE id=1");
            return AgentService.ChatResult.contentOnly(structured("[report](/api/v1/files/generated/12345678-abcd-1234-abcd-123456789abc)"));
        });
        dispatch(agent).runTask(2L, tasks.getTask(1L));
        assertEquals(reassigned ? TeamTaskStatus.IN_PROGRESS : TeamTaskStatus.COMPLETED, tasks.getTask(1L).getStatus());
        assertEquals(reassigned ? 0 : 1, tasks.listDeliverables(tasks.getTask(1L)).size());
        assertEquals(reassigned ? "NOT_APPLIED" : "APPLIED", jdbc.queryForObject("SELECT outcome FROM mate_decision_outcome", String.class));
    }

    @Test void workerAuditFailureRetainsTaskInsteadOfGenericFailure() {
        var agent = mock(AgentService.class);
        when(agent.chatWithUsage(eq(3L), anyString(), anyString())).thenReturn(AgentService.ChatResult.contentOnly("done"));
        doThrow(new IllegalStateException("unavailable")).when(records).outcome(any(), any(), any());
        dispatch(agent).runTask(2L, tasks.getTask(1L));
        assertEquals(TeamTaskStatus.IN_PROGRESS, tasks.getTask(1L).getStatus());
        assertNull(tasks.getTask(1L).getReason());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_team_task_event", Integer.class));
    }

    @Test void decisionPreparationFailureRetainsWorkerState() {
        var agent = mock(AgentService.class);
        when(agent.chatWithUsage(eq(3L), anyString(), anyString())).thenReturn(AgentService.ChatResult.contentOnly("done"));
        doReturn(null).doThrow(new IllegalStateException("workspace unavailable")).when(teams).getTeam(2L);
        dispatch(agent).runTask(2L, tasks.getTask(1L));
        assertEquals(TeamTaskStatus.IN_PROGRESS, tasks.getTask(1L).getStatus());
        assertNull(tasks.getTask(1L).getReason());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_team_task_event", Integer.class));
    }

    @ParameterizedTest @ValueSource(strings = {"complete", "fail", "requeue", "checkpoint"})
    void snapshotCannotAuthorizeMutationOfAnotherTask(String action) {
        jdbc.update("INSERT INTO mate_team_task(id,team_id,task_number,subject,status,assignee_agent_id,owner_agent_id,dispatch_count,conversation_id,require_approval) VALUES(2,2,2,'Other task','in_progress',7,7,1,'other-worker',TRUE)");
        var first = tasks.getTask(1L); var snapshot = WorkerDecisionAdapter.Snapshot.capture(first);
        var ticket = adapter.judge(first, "VALID", "done").ticket();
        boolean applied = switch (action) {
            case "complete" -> tasks.completeTask(2L, null, "wrong task", ticket, snapshot).applied();
            case "fail" -> tasks.failTask(2L, "wrong task", ticket, snapshot);
            case "requeue" -> tasks.requeueUnusableResult(2L, "wrong task", ticket, snapshot);
            default -> tasks.parkWorkerCheckpoint(2L, 10, "wrong task", ticket, snapshot);
        };
        assertFalse(applied);
        assertEquals(TeamTaskStatus.IN_PROGRESS, tasks.getTask(2L).getStatus());
        assertNull(tasks.getTask(2L).getResult());
        assertNull(tasks.getTask(2L).getProgressPercent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_team_task_event", Integer.class));
        assertEquals("NOT_APPLIED", jdbc.queryForObject("SELECT outcome FROM mate_decision_outcome", String.class));
    }

    private TeamDispatchService dispatch(AgentService agent) {
        var dispatch = new TeamDispatchService(teams, tasks, agent, mock(ConversationService.class), mock(ChatStreamTracker.class),
                mock(TeamAnnounceService.class), mock(TeamEventChannel.class), mock(ApprovalWorkflowService.class));
        dispatch.setDecisionAdapter(adapter); return dispatch;
    }

    @Test void explicitCompletionUsesOneGuardedAcceptedObservation() {
        tasks.completeTask(1L, 3L, "explicitly done");
        assertEquals(TeamTaskStatus.COMPLETED, tasks.getTask(1L).getStatus());
        assertEquals("OBSERVED", jdbc.queryForObject("SELECT outcome FROM mate_decision_outcome", String.class));
        assertEquals("true", jdbc.queryForObject("SELECT baseline_value FROM mate_decision_record", String.class));
        assertEquals("GUARD", jdbc.queryForObject("SELECT reason FROM mate_decision_record", String.class));
    }
}
