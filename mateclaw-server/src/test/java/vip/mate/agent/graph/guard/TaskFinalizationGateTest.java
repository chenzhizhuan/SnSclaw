package vip.mate.agent.graph.guard;

import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.GraphEventPublisher;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskFinalizationGateTest {
    private final TaskFinalizationGate gate = new TaskFinalizationGate();
    private String json(String decision, boolean complete, boolean remaining, int count, String claims) {
        return "{\"decision\":\""+decision+"\",\"taskComplete\":"+complete+",\"remainingWork\":"+remaining+",\"failedCallCount\":"+count+",\"recoveryClaims\":"+claims+",\"reason\":\"checked\"}";
    }
    private ActionExecutionLedger changedParameterReceipts() {
        return ActionExecutionLedger.fromEvents(List.of(
            GraphEventPublisher.toolStart("f","execute_code","{\"code\":\"print(1)\"}"),
            GraphEventPublisher.toolStart("s","execute_code","{\"code\":\"print(1)\",\"language\":\"python\"}"),
            new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,Map.of("toolCallId","f","toolName","execute_code","result","missing language","success",false),10),
            new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,Map.of("toolCallId","s","toolName","execute_code","result","1","success",true),11)));
    }
    @Test void knownWrapperFailureWithoutOperationIdentityCanOnlyRequestClaimRemoval() {
        var ledger=ActionExecutionLedger.fromEvents(List.of(
            GraphEventPublisher.toolStart("f","tool_call","{\"toolName\":\"execute_code\",\"arguments\":{\"code\":\"print(1)\"}}"),
            GraphEventPublisher.toolStart("s","execute_code","{\"code\":\"print(1)\",\"language\":\"python\"}"),
            GraphEventPublisher.toolComplete("f","tool_call","missing language",false),GraphEventPublisher.toolComplete("s","execute_code","1",true)));
        assertEquals("unknown",ledger.receipts().get("f").operationIdentity());
        var verdict=gate.parse(json("ACCEPT",true,false,1,"[{\"failedCallId\":\"f\",\"successfulCallId\":\"s\"}]")).orElseThrow();
        assertEquals(TaskFinalizationGate.Outcome.REVISE,gate.evaluate(verdict,ledger,0,false,true).outcome());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(verdict,ledger,2,false,true).outcome());
    }
    @Test void knownFalseRecoveryRelationRequiresBoundedTrustedRevisionNeverAcceptance() {
        var ledger=changedParameterReceipts();
        String claims="[{\"failedCallId\":\"f\",\"successfulCallId\":\"s\"}]";
        for(String decision:List.of("ACCEPT","REVISE","CONTINUE")) {
            boolean continuation=decision.equals("CONTINUE");
            var verdict=gate.parse(json(decision,!continuation,continuation,1,claims).replace("checked","UNTRUSTED_CRITIC")).orElseThrow();
            for(int attempts:List.of(0,1)) {
                var result=gate.evaluate(verdict,ledger,attempts,false,true);
                assertEquals(TaskFinalizationGate.Outcome.REVISE,result.outcome());
                assertTrue(result.reason().contains("does not establish"));assertFalse(result.reason().contains("UNTRUSTED_CRITIC"));
            }
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(verdict,ledger,2,false,true).outcome());
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(verdict,ledger,0,false,false).outcome());
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(verdict,ledger,0,true,true).outcome());
        }
        for(String claim:List.of(claims.replace("\"f\"","\"forged\""),claims.replace("\"s\"","\"f\""),
                claims.substring(0,claims.length()-1)+",{\"failedCallId\":\"forged\",\"successfulCallId\":\"s\"}]")) {
            var verdict=gate.parse(json("ACCEPT",true,false,1,claim)).orElseThrow();
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(verdict,ledger,0,false,true).outcome());
        }
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(gate.parse(json("ACCEPT",true,true,1,claims)).orElseThrow(),ledger,0,false,true).outcome());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(gate.parse(json("ACCEPT",false,false,1,claims)).orElseThrow(),ledger,0,false,true).outcome());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(gate.parse(json("CONTINUE",true,false,1,claims)).orElseThrow(),ledger,0,false,true).outcome());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(gate.parse(json("ACCEPT",true,false,0,claims)).orElseThrow(),ledger,0,false,true).outcome());
    }
    @Test void strictParserRejectsMalformedAndCoercedVerdicts() {
        String valid = json("ACCEPT",true,false,0,"[]");
        assertTrue(gate.parse(valid).isPresent());
        for (String bad : List.of("```json\n"+valid+"\n```", valid+" {}", "[]", "null", valid.replace("0,", "0.0,"), valid.replace("0,", "\"0\","), valid.replace("true", "\"true\""), valid.replace("\"reason\":", "\"extra\":1,\"reason\":"), valid.replace("\"reason\":", "\"decision\":\"ACCEPT\",\"reason\":"), valid.replace("checked", "x".repeat(1025)), " ".repeat(17000)+valid, valid.replace("0,", "NaN,"))) {
            assertTrue(gate.parse(bad).isEmpty(), bad.substring(0, Math.min(100,bad.length())));
        }
    }
    @Test void strictParserRejectsMissingFieldsInvalidClaimsAndInvalidDecisions() {
        String valid = json("ACCEPT", true, false, 0, "[]");
        for (String bad : List.of(valid.replace("\"reason\":\"checked\"", "\"reason\":null"),
                valid.replace("\"ACCEPT\"", "\"INCOMPLETE\""), valid.replace("0,", "-1,"),
                valid.replace("0,", "2147483648,"), valid.replace(",\"reason\":\"checked\"", ""),
                valid.replace("[]", "{}"), valid.replace("[]", "[{}]"),
                valid.replace("[]", "[{\"failedCallId\":1,\"successfulCallId\":\"x\"}]"),
                valid.replace("[]", "[{\"failedCallId\":\"f\",\"successfulCallId\":\"s\",\"extra\":false}]"))) {
            assertTrue(gate.parse(bad).isEmpty(), bad);
        }
    }

    @Test void supportedRecoveryClaimIsValidatedAgainstCanonicalReceipts() {
        var ledger=ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolStart("f", "calculate", "{\"x\":1}"),
                GraphEventPublisher.toolStart("s", "calculate", "{\"x\":1}"),
                new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                        Map.of("toolCallId","f","toolName","calculate","result","fail","success",false), 10),
                new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                        Map.of("toolCallId","s","toolName","calculate","result","1","success",true), 11)));
        var verdict=gate.parse(json("ACCEPT",true,false,1,
                "[{\"failedCallId\":\"f\",\"successfulCallId\":\"s\"}]")).orElseThrow();
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,gate.evaluate(verdict,ledger,0,false,true).outcome());
        assertTrue(ledger.renderAnswerFacts().contains("\"supportedRecoveryCallIds\":[\"s\"]"));
        var incompleteTask=gate.parse(json("ACCEPT",false,false,1,"[]")).orElseThrow();
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(incompleteTask,ledger,0,false,true).outcome());
    }

    @Test void invalidUnavailableAndIncorrectCountsAreIncomplete() {
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE, gate.evaluate(null, ActionExecutionLedger.empty(),0,false,true).outcome());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE, gate.evaluate(gate.parse(json("ACCEPT",true,false,1,"[]")).orElseThrow(), ActionExecutionLedger.empty(),0,false,true).outcome());
    }
    @Test void taskCompletionDoesNotImplyOriginalOperationRecovery() {
        var ledger=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("f","tool","fail",false),GraphEventPublisher.toolComplete("s","tool","done",true)));
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,gate.evaluate(gate.parse(json("ACCEPT",true,false,1,"[]")).orElseThrow(),ledger,0,false,true).outcome());
        assertEquals(TaskFinalizationGate.Outcome.REVISE,gate.evaluate(gate.parse(json("ACCEPT",true,false,1,
            "[{\"failedCallId\":\"f\",\"successfulCallId\":\"s\"}]")).orElseThrow(),ledger,0,false,true).outcome());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(gate.parse(json("ACCEPT",true,false,1,
            "[{\"failedCallId\":\"forged\",\"successfulCallId\":\"s\"}]")).orElseThrow(),ledger,0,false,true).outcome());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(gate.parse(json("ACCEPT",true,true,1,"[]")).orElseThrow(),ledger,0,false,true).outcome());
    }
    @Test void continuationRequiresUnfinishedTaskAndRemainingWork() {
        for (boolean complete : List.of(false, true)) {
            for (boolean remaining : List.of(false, true)) {
                var verdict = gate.parse(json("CONTINUE",complete,remaining,0,"[]")).orElseThrow();
                assertEquals(!complete && remaining ? TaskFinalizationGate.Outcome.CONTINUE : TaskFinalizationGate.Outcome.INCOMPLETE,
                        gate.evaluate(verdict,ActionExecutionLedger.empty(),0,false,true).outcome());
            }
        }
        var revision=gate.parse(json("REVISE",true,false,0,"[]")).orElseThrow();
        assertEquals(TaskFinalizationGate.Outcome.REVISE,gate.evaluate(revision,ActionExecutionLedger.empty(),0,false,true).outcome());
    }

    @Test void unfinishedRevisionRoutesToExecutionRatherThanProseOnlyRepair() {
        var verdict=gate.parse(json("REVISE",false,true,0,"[]")).orElseThrow();
        assertEquals(TaskFinalizationGate.Outcome.CONTINUE,
                gate.evaluate(verdict,ActionExecutionLedger.empty(),0,false,true).outcome());
        for(int attempts:List.of(0,1)) {
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,
                    gate.evaluate(verdict,ActionExecutionLedger.empty(),attempts,false,false).outcome());
        }
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,
                gate.evaluate(verdict,ActionExecutionLedger.empty(),2,false,true).outcome());
    }
    @Test void attemptsAreTotalAndTerminalStateTakesPrecedence() {
        for(String decision:List.of("CONTINUE","REVISE")) {
            var verdict=gate.parse(json(decision,false,true,0,"[]")).orElseThrow();
            assertEquals(TaskFinalizationGate.Outcome.CONTINUE,gate.evaluate(verdict,ActionExecutionLedger.empty(),1,false,true).outcome());
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(verdict,ActionExecutionLedger.empty(),2,false,true).outcome());
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(verdict,ActionExecutionLedger.empty(),0,true,true).outcome());
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(verdict,ActionExecutionLedger.empty(),0,false,false).outcome());
        }
        var accept=gate.parse(json("ACCEPT",true,false,0,"[]")).orElseThrow();
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(accept,ActionExecutionLedger.empty(),0,true,true).outcome());
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,gate.evaluate(accept,ActionExecutionLedger.empty(),2,false,false).outcome());
    }
    @Test void strictParserReportsFixedFailureCategoriesWithoutChangingAcceptance() throws Exception {
        java.lang.reflect.Method diagnose;
        try { diagnose=gate.getClass().getMethod("parseFailureCode",String.class); }
        catch(NoSuchMethodException missing){throw new AssertionError("Strict parse rejection needs a safe fixed diagnostic",missing);}
        String valid=json("ACCEPT",true,false,0,"[]");
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put(valid,"NONE");cases.put("","EMPTY_INPUT");cases.put(" ".repeat(17000)+valid,"VERDICT_SIZE_LIMIT");
        cases.put("```json\n"+valid+"\n```","FENCED_JSON");cases.put("not json","INVALID_JSON");
        cases.put(valid+" {}","TRAILING_CONTENT");cases.put("[]","NON_OBJECT");
        cases.put(valid.replace("\"reason\":","\"decision\":\"ACCEPT\",\"reason\":"),"DUPLICATE_FIELDS");
        cases.put(valid.replace(",\"reason\":\"checked\"",""),"FIELD_SET");
        cases.put(valid.replace("true","\"true\""),"FIELD_TYPES");
        cases.put(valid.replace("0,","-1,"),"COUNT_RANGE");
        cases.put(valid.replace("ACCEPT","INCOMPLETE"),"INVALID_DECISION");
        cases.put(valid.replace("checked","x".repeat(1025)),"REASON_LIMIT");
        cases.put(valid.replace("[]","["+String.join(",",Collections.nCopies(65,"{\"failedCallId\":\"f\",\"successfulCallId\":\"s\"}"))+"]"),"CLAIMS_LIMIT");
        cases.put(valid.replace("[]","[{\"failedCallId\":\"f\",\"successfulCallId\":\"s\",\"SECRET_UNKNOWN_FIELD\":1}]"),"CLAIM_FIELD_SET");
        cases.put(valid.replace("[]","[{\"failedCallId\":\"\",\"successfulCallId\":\"s\"}]"),"CLAIM_ID");
        for(var entry:cases.entrySet()) {
            assertEquals(entry.getValue(),String.valueOf(diagnose.invoke(gate,entry.getKey())));
            assertEquals(entry.getValue().equals("NONE"),gate.parse(entry.getKey()).isPresent());
        }
    }
}
