package vip.mate.agent.graph.state;

import org.junit.jupiter.api.Test;
import vip.mate.agent.GraphEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionExecutionLedgerTest {

    @Test void legacyReceiptLengthIsUnknownAndCannotGroundLatestOutput() throws Exception {
        var receipt=new ActionExecutionLedger.Receipt("legacy","calc",ActionExecutionLedger.Status.SUCCEEDED,"x".repeat(512)+"...",1);
        assertEquals(-1,receipt.resultChars());
        var restored=new com.fasterxml.jackson.databind.ObjectMapper().readValue(
            "{\"toolCallId\":\"old\",\"toolName\":\"calc\",\"status\":\"SUCCEEDED\",\"resultSummary\":\"old preview\",\"completedAt\":1}",ActionExecutionLedger.Receipt.class);
        assertEquals(-1,restored.resultChars());
        var constructor=ActionExecutionLedger.class.getDeclaredConstructor(java.util.Map.class);constructor.setAccessible(true);
        var ledger=constructor.newInstance(java.util.Map.of("legacy",receipt));
        assertFalse(ledger.projectAnswerFacts(3000,java.util.Map.of("legacy","x".repeat(600))).coreFactsFit());
        var projection=new com.fasterxml.jackson.databind.ObjectMapper().readTree(ledger.projectAnswerFacts(3000).text());
        var output=projection.path("successfulSubstantiveCalls").get(0);
        assertFalse(output.path("resultComplete").asBoolean());assertEquals(-1,output.path("omittedResultChars").asInt());
    }
    @Test void guardedProjectionReservesFullLatestOutputBeforeOptionalOlderReceipts() throws Exception {
        String old="old "+"x".repeat(600), latest="y".repeat(600)+" totalSquareSum=2094116873200";
        var ledger=ActionExecutionLedger.fromEvents(List.of(
            new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,java.util.Map.of("toolCallId","old","toolName","calc","result",old,"success",true),1),
            new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,java.util.Map.of("toolCallId","latest","toolName","calc","result",latest,"success",true),2)));
        var full=ledger.projectAnswerFacts(3000,java.util.Map.of("latest",latest));
        assertTrue(full.coreFactsFit());assertTrue(full.text().contains("totalSquareSum=2094116873200"));
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(full.text());
        assertTrue(json.path("successfulSubstantiveCalls").get(0).path("resultComplete").asBoolean());
        assertFalse(json.path("successfulSubstantiveCalls").get(1).path("resultComplete").asBoolean());
        assertEquals(old.length()-512,json.path("successfulSubstantiveCalls").get(1).path("omittedResultChars").asInt());
        int mandatoryBudget=vip.mate.agent.context.TokenEstimator.estimateTokens(
            ledger.projectAnswerFacts(3000,java.util.Map.of("latest",latest)).text());
        assertFalse(ledger.projectAnswerFacts(64,java.util.Map.of("latest",latest)).coreFactsFit());
        assertFalse(ledger.projectAnswerFacts(3000,java.util.Map.of()).coreFactsFit());
        assertFalse(ledger.projectAnswerFacts(3000,java.util.Map.of("latest",latest+"forged")).coreFactsFit());
        boolean omitted=false;
        for(int budget=64;budget<mandatoryBudget;budget++) {
            var projection=ledger.projectAnswerFacts(budget,java.util.Map.of("latest",latest));
            if(projection.coreFactsFit()) {
                assertTrue(projection.text().contains("totalSquareSum=2094116873200"));
                omitted=new com.fasterxml.jackson.databind.ObjectMapper().readTree(projection.text()).path("omittedSuccessfulSubstantiveCallCount").asInt()==1;
                if(omitted)break;
            }
        }
        assertTrue(omitted,"Full latest output must displace optional older previews");
    }
    @Test
    void recoveryRequiresKnownIdenticalRequestAndStrictlyLaterSuccess() {
        for (String tool : List.of("calculate", "tool_call")) {
            for (String args : List.of("{\"x\":1}", "{\"x\":2}")) {
                for (long time : List.of(9L, 10L, 11L)) {
                    var events = List.of(GraphEventPublisher.toolStart("f", tool, "{\"x\":1}"),
                            GraphEventPublisher.toolStart("s", tool, args),
                            new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                                    java.util.Map.of("toolCallId","f","toolName",tool,"result","fail","success",false),10),
                            new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                                    java.util.Map.of("toolCallId","s","toolName",tool,"result","ok","success",true),time));
                    var ledger = ActionExecutionLedger.fromEvents(events);
                    assertEquals(tool.equals("calculate") && args.equals("{\"x\":1}") && time > 10,
                            ledger.supportedRecovery("f", "s"));
                    assertFalse(ledger.supportedRecovery("s", "f"));
                    assertFalse(ledger.supportedRecovery("f", "missing"));
                }
            }
        }
    }

    @Test
    void tokenProjectionPreservesCountsAndPrioritizesNewestOutputWithoutMutatingReceipts() throws Exception {
        var events = new java.util.ArrayList<GraphEventPublisher.GraphEvent>();
        for (int i=0;i<30;i++) events.add(new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                java.util.Map.of("toolCallId","c"+i,"toolName","calculate","result","value"+i+"x".repeat(300),"success",i!=0),i));
        var ledger=ActionExecutionLedger.fromEvents(events);
        var before=ledger.receipts();
        var projection=ledger.projectAnswerFacts(400);
        assertTrue(projection.coreFactsFit());
        assertTrue(vip.mate.agent.context.TokenEstimator.estimateTokens(projection.text())<=400);
        var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(projection.text());
        assertEquals(1,data.path("failedCallCount").asInt());
        assertEquals(29,data.path("successfulCallCount").asInt());
        assertEquals("c29",data.path("successfulSubstantiveCalls").get(0).path("toolCallId").asText());
        assertEquals(29-data.path("successfulSubstantiveCalls").size(),data.path("omittedSuccessfulSubstantiveCallCount").asInt());
        assertEquals(before,ledger.receipts());
        assertFalse(ledger.projectAnswerFacts(1).coreFactsFit());
        assertTrue(ledger.projectAnswerFacts(1).text().isEmpty());
    }

    @Test
    void requiredProjectionCoreCannotStarveFailureEvidenceOrNewestOutput() throws Exception {
        var events = new java.util.ArrayList<GraphEventPublisher.GraphEvent>();
        events.add(GraphEventPublisher.toolStart("controlled-failure","calculate","{}"));
        events.add(GraphEventPublisher.toolStart("s30","calculate","{}"));
        events.add(new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                java.util.Map.of("toolCallId","controlled-failure","toolName","calculate","result","missing language","success",false),0));
        for (int i=1;i<=30;i++) events.add(new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                java.util.Map.of("toolCallId","s"+i,"toolName","calculate","result","output-"+i+"x".repeat(300),"success",true),i));
        var ledger = ActionExecutionLedger.fromEvents(events);
        var before = ledger.receipts();
        var projection = ledger.projectAnswerFacts(300);
        assertTrue(projection.coreFactsFit());
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(projection.text());
        assertEquals(1,data.path("failedCalls").size(), "Mandatory failure evidence must fit before optional successes");
        assertEquals("controlled-failure",data.path("failedCalls").get(0).path("toolCallId").asText());
        assertEquals("missing language",data.path("failedCalls").get(0).path("resultSummary").asText());
        assertEquals("s30",data.path("failedCalls").get(0).path("supportedRecoveryCallIds").get(0).asText());
        assertEquals("s30",data.path("successfulSubstantiveCalls").get(0).path("toolCallId").asText());
        assertEquals(30-data.path("successfulSubstantiveCalls").size(),data.path("omittedSuccessfulSubstantiveCallCount").asInt());
        assertFalse(ledger.projectAnswerFacts(100).coreFactsFit());
        assertTrue(ledger.projectAnswerFacts(100).text().isEmpty());
        assertEquals(before,ledger.receipts());
        assertFalse(ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("large","calculate","x".repeat(512),true)))
                .projectAnswerFacts(100).coreFactsFit());
    }

    private com.fasterxml.jackson.databind.JsonNode facts(ActionExecutionLedger ledger) throws Exception {
        String text = ledger.renderAnswerFacts();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
    }

    @Test
    void numericArgumentPrecisionAndLexicalTypeCannotCollapseDifferentOperations() throws Exception {
        var events = new java.util.ArrayList<GraphEventPublisher.GraphEvent>();
        var requests = List.of("{\"value\":1.0000000000000001}", "{\"value\":1.0}",
                "{\"value\":1}", "{\"value\":\"1\"}");
        for (int i = 0; i < requests.size(); i++) {
            events.add(GraphEventPublisher.toolStart("n" + i, "calculate", requests.get(i)));
            events.add(GraphEventPublisher.toolComplete("n" + i, "calculate", "done", true));
        }
        var identities = new java.util.HashSet<String>();
        for (var receipt : facts(ActionExecutionLedger.fromEvents(events)).path("successfulSubstantiveCalls")) {
            assertTrue(identities.add(receipt.path("operationIdentity").asText()));
        }
    }

    @Test
    void receiptConstructorsKeepLegacyAndExternallySuppliedIdentityBounded() {
        assertEquals("unknown", new ActionExecutionLedger.Receipt("id", "tool",
                ActionExecutionLedger.Status.SUCCEEDED, "ok", 0).operationIdentity());
        for (String unsafe : java.util.Arrays.asList(null, "raw-secret".repeat(1000), "sha256:bad")) {
            assertEquals("unknown", new ActionExecutionLedger.Receipt("id", "tool",
                    ActionExecutionLedger.Status.SUCCEEDED, "ok", 0, unsafe).operationIdentity());
        }
    }

    @Test
    void conflictingStartOrCompletionNamesCannotProvideIdentityEvidence() throws Exception {
        for (var starts : List.of(
                List.of(GraphEventPublisher.toolStart("call", "another_tool", "{}")),
                List.of(GraphEventPublisher.toolStart("call", "execute_code", "{\"x\":1}"),
                        GraphEventPublisher.toolStart("call", "execute_code", "{\"x\":2}")))) {
            var events = new java.util.ArrayList<>(starts);
            events.add(GraphEventPublisher.toolComplete("call", "execute_code", "1", true));
            assertEquals("unknown", facts(ActionExecutionLedger.fromEvents(events))
                    .path("successfulSubstantiveCalls").get(0).path("operationIdentity").asText());
        }
    }

    @Test
    void unsafeArgumentsNeverReceiveAnOperationIdentity() throws Exception {
        for (String arguments : List.of("", "not-json", "null", "[]", "{} {}", "{\"x\":1,\"x\":2}")) {
            var ledger = ActionExecutionLedger.fromEvents(List.of(
                    GraphEventPublisher.toolStart("call", "execute_code", arguments),
                    GraphEventPublisher.toolComplete("call", "execute_code", "done", true)));
            assertEquals("unknown", facts(ledger).path("successfulSubstantiveCalls").get(0)
                    .path("operationIdentity").asText(), arguments);
        }
    }

    @Test
    void zeroFailuresExplicitlyForbidsInventingFailedAttempts() {
        String facts = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("ok", "execute_code", "1", true))).renderAnswerFacts();
        assertTrue(facts.contains("\"failedCallCount\":0"));
        assertTrue(facts.contains("Do not invent failed attempts"));
    }

    @Test
    void operationIdentitiesJoinByCallIdAndDistinguishDifferentRequests() throws Exception {
        var ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolStart("one", "execute_code", "{\"code\":\"print(1)\",\"language\":\"python\"}"),
                GraphEventPublisher.toolStart("two", "execute_code", "{\"language\":\"python\",\"code\":\"print(2)\"}"),
                GraphEventPublisher.toolStart("three", "execute_code", "{\"code\":\"print(1)\",\"language\":\"python\"}"),
                GraphEventPublisher.toolStart("reordered", "execute_code", "{\"language\":\"python\",\"code\":\"print(1)\"}"),
                GraphEventPublisher.toolComplete("one", "execute_code", "failure", false),
                GraphEventPublisher.toolComplete("two", "execute_code", "2", true),
                GraphEventPublisher.toolComplete("three", "execute_code", "1", true),
                GraphEventPublisher.toolComplete("reordered", "execute_code", "1", true)));
        var data = facts(ledger);
        String failedIdentity = data.path("failedCalls").get(0).path("operationIdentity").asText();
        assertTrue(failedIdentity.matches("sha256:[a-f0-9]{64}"));
        var successes = data.path("successfulSubstantiveCalls");
        for (var success : successes) {
            if (!success.path("toolCallId").asText().equals("three")) {
                assertFalse(failedIdentity.equals(success.path("operationIdentity").asText()));
            } else {
                assertEquals(failedIdentity, success.path("operationIdentity").asText());
            }
        }
        assertFalse(ledger.renderAnswerFacts().contains("print("));
        assertTrue(ledger.renderAnswerFacts().contains("does not prove task completion"));
    }

    @Test
    void wrapperAndLegacyCallsWithoutSafeTargetIdentityRemainUnknown() throws Exception {
        var ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolStart("wrapper", "tool_call", "{\"toolName\":\"execute_code\",\"arguments\":{\"code\":\"print(1)\"}}"),
                GraphEventPublisher.toolComplete("wrapper", "tool_call", "missing language", false),
                GraphEventPublisher.toolComplete("old", "execute_code", "1", true)));
        var data = facts(ledger);
        assertEquals("unknown", data.path("failedCalls").get(0).path("operationIdentity").asText());
        assertEquals("unknown", data.path("successfulSubstantiveCalls").get(0).path("operationIdentity").asText());
        assertTrue(ledger.renderAnswerFacts().contains("unknown identities cannot establish recovery"));
    }

    @Test
    void duplicateEventsAndMergeKeepExactCountsAndBoundedOpaqueMetadata() throws Exception {
        var events = new java.util.ArrayList<GraphEventPublisher.GraphEvent>();
        for (int i = 0; i < 100; i++) {
            var start = GraphEventPublisher.toolStart("call-" + i, "execute_code", "{\"secret\":\"" + "s".repeat(10000) + "\"}");
            var end = GraphEventPublisher.toolComplete("call-" + i, "execute_code", "value", i % 2 == 0);
            events.add(start); events.add(end); events.add(end);
        }
        var ledger = ActionExecutionLedger.fromEvents(events);
        var merged = ledger.merge(ledger);
        var data = facts(merged);
        assertEquals(50, data.path("failedCallCount").asInt());
        assertEquals(50, data.path("successfulCallCount").asInt());
        assertEquals(38, data.path("omittedFailedCallCount").asInt());
        assertEquals(26, data.path("omittedSuccessfulSubstantiveCallCount").asInt());
        assertTrue(merged.renderAnswerFacts().length() < 12000);
        assertFalse(merged.renderAnswerFacts().contains("secret"));
        assertTrue(data.path("successfulSubstantiveCalls").get(0).path("operationIdentity").asText().startsWith("sha256:"));
    }

    @Test
    void oversizedReceiptIdentifiersDoNotInflateAnswerFacts() {
        var ledger = ActionExecutionLedger.fromEvents(java.util.List.of(
                vip.mate.agent.GraphEventPublisher.toolComplete("x".repeat(10000), "y".repeat(10000), "failed", false)));
        assertTrue(ledger.renderAnswerFacts().length() < 2000);
    }

    @Test
    void answerFactsPreserveFailuresWithoutInferringRecoveryFromSameToolSuccess() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("failed-1", "tool_call",
                        "{\"error\":true,\"code\":\"TOOL_ARGUMENTS_INCOMPLETE\"}", false),
                GraphEventPublisher.toolComplete("success-2", "tool_call", "different task succeeded", true)));

        String facts = ledger.renderAnswerFacts();
        assertTrue(facts.contains("failed-1"));
        assertTrue(facts.contains("TOOL_ARGUMENTS_INCOMPLETE"));
        assertTrue(facts.contains("success-2"));
        assertTrue(facts.contains("Do not claim that every execution succeeded"));
        assertTrue(facts.contains("does not establish recovery"));
    }

    @Test
    void successfulOnlyRunPreservesNumericResultsWithoutFailureInstruction() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("success", "execute_code", "{\"k\":3,\"n\":3300,\"sum\":11984445550}", true)));
        assertTrue(ledger.renderAnswerFacts().contains("11984445550"));
        assertTrue(ledger.renderAnswerFacts().contains("Copy numeric values verbatim"));
        assertFalse(ledger.renderAnswerFacts().contains("failed calls occurred"));
        assertEquals("", ActionExecutionLedger.empty().renderAnswerFacts());
    }

    @Test
    void successfulResultSamplesKeepLatestSubstantiveCallsAndExactOmittedCounts() {
        var events = new java.util.ArrayList<GraphEventPublisher.GraphEvent>();
        for (int i = 0; i < 30; i++) {
            events.add(new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                    java.util.Map.of("toolCallId", "call-" + i, "toolName", "execute_code",
                            "result", "value-" + i, "success", true), i));
        }
        events.add(GraphEventPublisher.toolComplete("progress", "progress_update", "do not preserve", true));
        String facts = ActionExecutionLedger.fromEvents(events).renderAnswerFacts();
        assertTrue(facts.contains("value-29"));
        assertFalse(facts.contains("value-0\""));
        assertFalse(facts.contains("do not preserve"));
        assertTrue(facts.contains("\"omittedSuccessfulSubstantiveCallCount\":6"));
    }

    @Test
    void disclosureOnlyRunAddsNoAnswerFacts() {
        var ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("load", "load_skill", "skill", true),
                GraphEventPublisher.toolComplete("describe", "tool_describe", "schema", true)));
        assertEquals("", ledger.renderAnswerFacts());
    }

    @Test
    void successfulBusinessToolSatisfiesActionCompletion() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call-1", "schedule_meeting", "created", true)));

        assertTrue(ledger.hasSubstantiveAttempt());
        assertTrue(ledger.hasSuccessfulSubstantiveCall());
        assertEquals(ActionExecutionLedger.Status.SUCCEEDED,
                ledger.receipts().get("call-1").status());
    }

    @Test
    void failedBusinessToolIsAnAttemptButNotSuccessfulEvidence() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call-2", "schedule_meeting", "HTTP 500", false)));

        assertTrue(ledger.hasSubstantiveAttempt());
        assertFalse(ledger.hasSuccessfulSubstantiveCall());
        assertEquals(ActionExecutionLedger.Status.FAILED,
                ledger.receipts().get("call-2").status());
    }

    @Test
    void disclosureToolsNeverSatisfyActionCompletion() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("load-1", "load_skill", "skill body", true),
                GraphEventPublisher.toolComplete("enable-1", "enable_tool", "enabled", true)));

        assertFalse(ledger.hasSubstantiveAttempt());
        assertFalse(ledger.hasSuccessfulSubstantiveCall());
        assertEquals(2, ledger.receipts().size());
    }

    @Test
    void mergePreservesReceiptsAcrossIterations() {
        ActionExecutionLedger first = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("load-1", "load_skill", "skill body", true)));
        ActionExecutionLedger second = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call-3", "schedule_meeting", "created", true)));

        ActionExecutionLedger merged = first.merge(second);

        assertEquals(2, merged.receipts().size());
        assertTrue(merged.hasSuccessfulSubstantiveCall());
    }
}
