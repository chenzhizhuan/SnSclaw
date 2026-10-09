package vip.mate.planning.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import vip.mate.agent.model.AgentEntity;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.provider.*;
import vip.mate.decision.record.JdbcDecisionRecordStore;
import vip.mate.planning.repository.*;
import vip.mate.planning.model.SubPlanEntity;
import java.util.*;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRoutingTransactionTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    DecisionProperties properties = new DecisionProperties();
    DecisionService core;
    JdbcDecisionRecordStore records;
    AgentMapper agents = mock(AgentMapper.class);
    AgentRoutingDecisionAdapter adapter;
    PlanningService planning;
    SubPlanMapper subs;
    AgentMapper realAgents;
    String proposal = "AGENT:3";
    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:routing_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V203__decision_record.sql")).execute(ds);
        jdbc.execute("CREATE TABLE mate_plan(id BIGINT PRIMARY KEY,agent_id VARCHAR(64),conversation_id VARCHAR(64),goal TEXT,status VARCHAR(32),total_steps INT,completed_steps INT,create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE mate_sub_plan(id BIGINT PRIMARY KEY,plan_id BIGINT,step_index INT,description TEXT,status VARCHAR(32),assigned_agent_id BIGINT,create_time TIMESTAMP,update_time TIMESTAMP)");
        var configuration = new MybatisConfiguration(); configuration.addMapper(PlanMapper.class); configuration.addMapper(SubPlanMapper.class); configuration.addMapper(AgentMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject()); realAgents=session.getMapper(AgentMapper.class); subs = spy(session.getMapper(SubPlanMapper.class));
        properties.setMode(DecisionMode.ACTIVE); records = spy(new JdbcDecisionRecordStore(jdbc,manager,properties));
        core = new DecisionService(properties,List.of(new RuleDecisionProvider() {
            @Override public DecisionResult decide(DecisionRequest request) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return DecisionResult.proposed(new DecisionValue.Choice(proposal),1,"test");
            }
        }),records,new SimpleMeterRegistry());
        when(agents.selectList(any())).thenReturn(List.of(AgentRoutingDecisionAdapterTest.agent(2,"Research"),AgentRoutingDecisionAdapterTest.agent(3,"Writing")));
        adapter = new AgentRoutingDecisionAdapter(core,properties,agents);
        var target = new PlanningService(session.getMapper(PlanMapper.class),subs); target.setRoutingAdapter(adapter);
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource())); planning=(PlanningService)proxy.getProxy();
    }
    @AfterEach void close() { core.close(); }
    AgentRoutingDecisionAdapter.Selection select(List<Long> baseline) { return adapter.select(10L,"1","conversation","summarize",List.of("read","write"),baseline); }
    void persist(AgentRoutingDecisionAdapter.Selection selection, List<Long> baseline) {
        planning.createPlan("1","conversation","summarize",List.of("read","write"),baseline,selection);
    }
    List<Long> assignments() { return jdbc.query("SELECT assigned_agent_id FROM mate_sub_plan ORDER BY step_index",(rs,i)->rs.getObject(1,Long.class)); }
    @Test void activePersistsActualFallbackWithMatchingOutcomesAfterAllInserts() {
        var baseline=Arrays.asList(2L,null); var selected=select(baseline);
        when(agents.selectList(any())).thenReturn(List.of(AgentRoutingDecisionAdapterTest.agent(2,"Research")));
        persist(selected,baseline);
        assertEquals(baseline,assignments());
        assertEquals(List.of("AGENT:2","LOCAL"),jdbc.queryForList("SELECT actual_value FROM mate_decision_outcome ORDER BY actual_value",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome WHERE outcome='NOT_APPLIED'",Integer.class));
    }
    @Test void auditFailureRollsBackPlanAndEverySubplan() {
        var selected=select(null); doThrow(new IllegalStateException("unavailable")).when(records).outcome(any(),any(),any());
        assertThrows(DecisionRecordingException.class,()->persist(selected,null)); assertEmptyBusiness();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record",Integer.class));
    }
    @Test void failedOrZeroRowInsertCannotCreateSuccessfulOutcome() {
        var selected=select(null); doAnswer(call -> {
            SubPlanEntity sub=call.getArgument(0); return sub.getStepIndex()==1 ? 0 : call.callRealMethod();
        }).when(subs).insert(any(SubPlanEntity.class));
        assertThrows(DecisionRecordingException.class,()->persist(selected,null)); assertEmptyBusiness();
    }
    @Test void outerRollbackRemovesActualOutcomesButKeepsProposal() {
        var selected=select(null);
        new TransactionTemplate(manager).executeWithoutResult(status->{persist(selected,null);status.setRollbackOnly();});
        assertEmptyBusiness();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record",Integer.class));
    }
    @Test void shadowStoresExactExcludedBaselineAndObservesOnlyCommittedPlan() throws Exception {
        properties.setMode(DecisionMode.SHADOW); var baseline=Arrays.asList(99L,null);
        var observed=new CountDownLatch(2); var recorded=new CountDownLatch(2);
        // Configure the spy before select starts asynchronous shadow writes.
        doAnswer(call->{call.callRealMethod();recorded.countDown();return null;}).when(records).insert(any());
        doAnswer(call->{call.callRealMethod();observed.countDown();return null;}).when(records).outcome(any(),any(),any());
        var selected=select(baseline);
        persist(selected,baseline); assertTrue(observed.await(5,TimeUnit.SECONDS));
        assertTrue(recorded.await(5,TimeUnit.SECONDS)); assertEquals(baseline,assignments());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome WHERE outcome='OBSERVED'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record WHERE baseline_value='AGENT:99'",Integer.class));
    }
    @Test void healthyShadowEvaluatesDifferentChoiceButObservesExactLegacyAssignments() throws Exception {
        properties.setMode(DecisionMode.SHADOW);
        var recorded=new CountDownLatch(2);var observed=new CountDownLatch(2);
        doAnswer(call->{call.callRealMethod();recorded.countDown();return null;}).when(records).insert(any());
        doAnswer(call->{call.callRealMethod();observed.countDown();return null;}).when(records).outcome(any(),any(),any());
        var baseline=Arrays.asList(2L,null);persist(select(baseline),baseline);
        assertTrue(recorded.await(5,TimeUnit.SECONDS));assertTrue(observed.await(5,TimeUnit.SECONDS));
        assertEquals(baseline,assignments());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record WHERE proposed_value='AGENT:3'",Integer.class));
        assertEquals(List.of("AGENT:2","LOCAL"),jdbc.queryForList("SELECT actual_value FROM mate_decision_outcome WHERE outcome='OBSERVED' ORDER BY actual_value",String.class));
    }
    @Test void shadowRollbackDoesNotScheduleOutcome() {
        properties.setMode(DecisionMode.SHADOW);var selected=select(null);
        new TransactionTemplate(manager).executeWithoutResult(status->{persist(selected,null);status.setRollbackOnly();});
        verify(records,never()).outcome(any(),any(),any()); assertEmptyBusiness();
    }
    @Test void offShadowPairedBusinessRowsSurviveCandidateChangesAndStoreFailures() {
        for (List<Long> baseline : Arrays.asList(null,Arrays.asList(2L,null),Arrays.asList(99L,null),List.of(3L,2L))) {
            for (boolean storeFailure : List.of(false,true)) {
                properties.setMode(DecisionMode.OFF); persist(null,baseline); var expected=businessRows(); clearBusiness();
                properties.setMode(DecisionMode.SHADOW);
                if(storeFailure)doThrow(new IllegalStateException("unavailable")).when(records).insert(any()); else doCallRealMethod().when(records).insert(any());
                when(agents.selectList(any())).thenReturn(List.of(AgentRoutingDecisionAdapterTest.agent(2,"Research"),AgentRoutingDecisionAdapterTest.agent(3,"Writing")));
                var selected=select(baseline); when(agents.selectList(any())).thenReturn(List.of());
                persist(selected,baseline);assertEquals(expected,businessRows());clearBusiness();
            }
        }
    }
    @Test void snowflakeAgentIdRemainsExactInAssignmentsAndAudit() {
        long id=9007199254740993L;proposal="AGENT:"+id;
        when(agents.selectList(any())).thenReturn(List.of(AgentRoutingDecisionAdapterTest.agent(id,"Specialist")));
        persist(select(null),null);assertEquals(List.of(id,id),assignments());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome WHERE actual_value=?",Integer.class,proposal));
    }
    @Test void candidateLookupFailureAtPersistenceFailsClosed() {
        var selected=select(null); when(agents.selectList(any())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(DecisionRecordingException.class,()->persist(selected,null)); assertEmptyBusiness();
    }
    @Test void realLockingReadRejectsConcurrentDisableAndKeepsEligibleBaseline() throws Exception {
        var info=TableInfoHelper.getTableInfo(AgentEntity.class);
        var columns=new ArrayList<String>(); columns.add("id BIGINT PRIMARY KEY");
        info.getFieldList().forEach(field -> columns.add(field.getColumn()+" "+
                (field.getPropertyType()==Boolean.class?"BOOLEAN":field.getPropertyType()==Long.class?"BIGINT":
                        field.getPropertyType()==Integer.class?"INT":field.getPropertyType()==LocalDateTime.class?"TIMESTAMP":"VARCHAR(2000)")));
        jdbc.execute("CREATE TABLE mate_agent("+String.join(",",columns)+")");
        jdbc.update("INSERT INTO mate_agent(id,name,workspace_id,enabled,deleted) VALUES(2,'Research',10,TRUE,0),(3,'Writing',10,TRUE,0)");
        var realAdapter=new AgentRoutingDecisionAdapter(core,properties,realAgents);planning.setRoutingAdapter(realAdapter);
        var selected=realAdapter.select(10L,"1","conversation","summarize",List.of("read","write"),Arrays.asList(2L,null));
        var started=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<?> pending=new TransactionTemplate(manager).execute(status->{
                jdbc.queryForList("SELECT id FROM mate_agent WHERE id=3 FOR UPDATE");
                var future=executor.submit(()->{started.countDown();persist(selected,Arrays.asList(2L,null));});
                try { assertTrue(started.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get(100,TimeUnit.MILLISECONDS)); }
                catch(InterruptedException failure){throw new RuntimeException(failure);}
                jdbc.update("UPDATE mate_agent SET enabled=FALSE WHERE id=3");return future;
            });
            pending.get(5,TimeUnit.SECONDS);
        }
        assertEquals(Arrays.asList(2L,null),assignments());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome WHERE outcome='NOT_APPLIED'",Integer.class));
    }
    List<Map<String,Object>> businessRows(){return jdbc.queryForList("SELECT p.agent_id,p.conversation_id,p.goal,p.status AS plan_status,p.total_steps,p.completed_steps,s.step_index,s.description,s.status,s.assigned_agent_id FROM mate_plan p JOIN mate_sub_plan s ON p.id=s.plan_id ORDER BY s.step_index");}
    void clearBusiness(){jdbc.update("DELETE FROM mate_sub_plan");jdbc.update("DELETE FROM mate_plan");}
    void assertEmptyBusiness(){assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_plan",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_sub_plan",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome",Integer.class));}
}
