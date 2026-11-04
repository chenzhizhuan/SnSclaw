package vip.mate.agent.graph.node;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.guard.TaskFinalizationCoordinator;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class ReasoningNodeUserQuestionTest {
    @Test
    void skillOnlyTurnEndsAtUserQuestionWithoutForcingAction() throws Exception {
        for (boolean finalization : List.of(false, true)) {
            assertWaits("需要我进一步查该任务的数据质量门禁（yjy_task_quality）或与其他任务对比吗？", "load_skill", finalization, false);
            assertWaits("**请确认是否继续执行？**", "load_skill", finalization, false);
            assertWaits("- Please provide the task ID.", "load_skill", finalization, false);
            assertWaits("Which task would you like me to query?", "load_skill", finalization, false);
        }
    }

    @Test
    void completedQueryFollowUpDoesNotEnterFinalizationOrLongFormContinuation() throws Exception {
        assertWaits("以上是任务 A 的执行概况。需要我进一步查数据质量门禁吗？", "query_task", true, true);
    }

    @Test
    void failedToolCanAskForMissingInputWithoutReplacingQuestionWithFailure() throws Exception {
        assertWaits("请提供任务 ID。", "failed_query", true, false);
    }

    @Test
    void unsupportedCompletionBeforeQuestionIsNotPublishedOrContinued() throws Exception {
        for (String tool : List.of("load_skill", "failed_query")) {
            var model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(ReasoningNodeFinalizationTest.response("已完成删除。需要我继续吗？")));
            var tracker = mock(ChatStreamTracker.class);
            var tools = mock(AgentToolSet.class);
            when(tools.callbacks()).thenReturn(List.of());
            var node = new ReasoningNode(model, tools, null, new NodeStreamingChatHelper(tracker), null, tracker);
            var values = ReasoningNodeFinalizationTest.state();
            values.put(ACTION_COMPLETION_REQUIRED, true);
            values.put(ACTION_EXECUTION_LEDGER, ActionExecutionLedger.fromEvents(List.of(
                    GraphEventPublisher.toolComplete("call", tool, "result", tool.equals("load_skill")))));
            var out = node.apply(new RunState(values));
            assertEquals("需要我继续吗？", out.get(FINAL_ANSWER));
            assertNotEquals(true, out.get(CONTINUE_REASONING));
            verify(model, times(1)).stream(any(Prompt.class));
        }
    }

    @Test
    void sourceAppendixAfterQuestionDoesNotForceAction() throws Exception {
        String question = "需要我按北京时间查询当前日期时间吗？";
        for (String appendix : List.of(
                "\n\n---\n\n**已加载技能来源信息**\n- 技能名称：`issue-668-confirmation`\n- 允许使用的工具：`getCurrentDateTime`\n\n本轮未执行任何日期时间查询工具。",
                "\n\n来源：\n[1] issue-668-confirmation/SKILL.md",
                "\n\n### Sources\n- Loaded local confirmation skill",
                "\n\n**技能来源信息**\n- 技能名称：issue-668-confirmation",
                "\n\n已加载技能的来源信息：\n- issue-668-confirmation/SKILL.md")) {
            assertWaits(question + appendix, "load_skill", true, false, question, "ACCEPT");
        }
    }

    @Test
    void querySummaryAndQuestionBeforeSourcesStillWaitWhenReviewerRequestsContinuation() throws Exception {
        String question = "需要我进一步查询当前时间吗？";
        String answer = "当前日期：2026-10-23。\n\n" + question
                + "\n\n查询来源：\n- getCurrentDate 返回 2026-10-23";
        assertWaits(answer, "query_task", true, false, question, "CONTINUE");
    }

    @Test
    void ordinaryAnswerKeepsExplanationBeforeFollowUpQuestion() throws Exception {
        String answer = "2 + 2 = 4. Would you like another example?";
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(ReasoningNodeFinalizationTest.response(answer)));
        var tracker = mock(ChatStreamTracker.class);
        var tools = mock(AgentToolSet.class);
        when(tools.callbacks()).thenReturn(List.of());
        var node = new ReasoningNode(model, tools, null, new NodeStreamingChatHelper(tracker), null, tracker);
        var values = ReasoningNodeFinalizationTest.state();
        values.put(ACTION_COMPLETION_REQUIRED, false);
        values.put(ACTION_EXECUTION_LEDGER, ActionExecutionLedger.empty());
        var out = node.apply(new RunState(values));
        assertEquals(answer, out.get(FINAL_ANSWER));
        assertNotEquals(true, out.get(CONTINUE_REASONING));
        verify(model, times(1)).stream(any(Prompt.class));
    }

    @Test
    void rejectedSummaryKeepsQuestionWithoutFollowingReviewerContinuation() throws Exception {
        for (String decision : List.of("REVISE", "CONTINUE", "invalid")) {
            assertWaits("已完成删除。需要我继续吗？", "query_task", true, false,
                    "需要我继续吗？", decision);
        }
    }

    @Test
    void rhetoricalOrQuotedQuestionsDoNotBypassMissingActionGuard() throws Exception {
        for (String text : List.of("已完成操作。", "为什么需要质量检查？", "示例：\"需要我继续吗？\"", "需要我继续吗？我现在就执行查询。", "*", "问题示例：\n> 需要我继续吗？")) {
            var model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(ReasoningNodeFinalizationTest.response(text)));
            var tools = mock(AgentToolSet.class);
            when(tools.callbacks()).thenReturn(List.of());
            var tracker = mock(ChatStreamTracker.class);
            var node = new ReasoningNode(model, tools, null, new NodeStreamingChatHelper(tracker), null, tracker);
            var values = ReasoningNodeFinalizationTest.state();
            values.put(ACTION_COMPLETION_REQUIRED, true);
            values.put(ACTION_EXECUTION_LEDGER, ActionExecutionLedger.empty());
            assertEquals(true, node.apply(new RunState(values)).get(CONTINUE_REASONING), text);
        }
    }

    private void assertWaits(String question, String toolName, boolean finalization, boolean longForm) throws Exception {
        assertWaits(question, toolName, finalization, longForm, question, "ACCEPT");
    }

    private void assertWaits(String question, String toolName, boolean finalization, boolean longForm,
                             String expected, String decision) throws Exception {
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            String prompt = ((Prompt) call.getArgument(0)).getContents();
            return Flux.just(ReasoningNodeFinalizationTest.response(prompt.contains("You are a tool-free consistency reviewer")
                    ? "{\"decision\":\"" + decision + "\",\"taskComplete\":" + !decision.equals("CONTINUE")
                            + ",\"remainingWork\":" + decision.equals("CONTINUE")
                            + ",\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"Summary review\"}"
                    : question));
        });
        var tools = mock(AgentToolSet.class);
        when(tools.callbacks()).thenReturn(List.of());
        var tracker = mock(ChatStreamTracker.class);
        var helper = new NodeStreamingChatHelper(tracker);
        var node = new ReasoningNode(model, tools, null, helper, null, tracker);
        if (finalization) node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper, tracker));
        var values = ReasoningNodeFinalizationTest.state();
        values.put(ACTION_COMPLETION_REQUIRED, true);
        values.put(ACTION_EXECUTION_LEDGER, ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call", toolName, "result", !toolName.equals("failed_query")))));
        if (longForm) values.put(USER_MESSAGE, "请根据工具结果写一篇10000字技术报告");
        var out = node.apply(new RunState(values));
        assertEquals(expected, out.get(FINAL_ANSWER));
        assertNotEquals(true, out.get(CONTINUE_REASONING));
        assertEquals(false, out.get(NEEDS_TOOL_CALL));
        assertEquals("normal", out.get(FINISH_REASON));
        verify(model, times(toolName.equals("query_task") && finalization ? 2 : 1)).stream(any(Prompt.class));
    }
}
