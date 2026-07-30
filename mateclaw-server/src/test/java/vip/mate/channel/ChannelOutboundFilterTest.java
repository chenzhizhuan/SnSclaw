package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.model.ChannelEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbound message filtering: the {@code filter_thinking} /
 * {@code filter_tool_messages} channel toggles.
 *
 * <p>Card-based streaming adapters (DingTalk AI Card, Feishu CardKit) never
 * call {@code renderAndSend} — they own their own length handling — so they
 * apply the filters through {@code filterOutboundContent}. These tests pin
 * that helper's contract so those paths cannot silently go unfiltered again.
 */
class ChannelOutboundFilterTest {

    /** Minimal concrete adapter — only the config plumbing is under test. */
    private static class StubAdapter extends AbstractChannelAdapter {
        StubAdapter(String configJson) {
            super(entity(configJson), null, new ObjectMapper());
        }

        private static ChannelEntity entity(String configJson) {
            ChannelEntity e = new ChannelEntity();
            e.setId(1L);
            e.setName("stub");
            e.setChannelType("stub");
            e.setConfigJson(configJson);
            return e;
        }

        @Override protected void doStart() {}
        @Override protected void doStop() {}
        @Override public void sendMessage(String targetId, String content) {}
        @Override public String getChannelType() { return "stub"; }

        String filter(String content) { return filterOutboundContent(content); }
    }

    private static final String RAW = """
            <think>weighing the options</think>
            Action: search
            Action Input: {"q": "weather"}
            Observation: sunny
            <tool_call>{"name":"search"}</tool_call>
            It will be sunny tomorrow.""";

    @Test
    @DisplayName("filter_thinking + filter_tool_messages = true strips markers from outbound text")
    void filtersBothWhenEnabled() {
        String out = new StubAdapter("{\"filter_thinking\":true,\"filter_tool_messages\":true}").filter(RAW);
        assertEquals("It will be sunny tomorrow.", out);
    }

    @Test
    @DisplayName("Filtering is on by default when the keys are absent")
    void defaultsToFiltering() {
        String out = new StubAdapter("{}").filter(RAW);
        assertEquals("It will be sunny tomorrow.", out);
    }

    @Test
    @DisplayName("filter_* = false leaves the markers in place")
    void keepsMarkersWhenDisabled() {
        String out = new StubAdapter("{\"filter_thinking\":false,\"filter_tool_messages\":false}").filter(RAW);
        assertTrue(out.contains("<think>weighing the options</think>"));
        assertTrue(out.contains("Observation: sunny"));
        assertTrue(out.contains("<tool_call>"));
    }

    @Test
    @DisplayName("Each toggle acts independently")
    void togglesAreIndependent() {
        String thinkingOnly = new StubAdapter(
                "{\"filter_thinking\":true,\"filter_tool_messages\":false}").filter(RAW);
        assertTrue(!thinkingOnly.contains("<think>"), "thinking should be stripped");
        assertTrue(thinkingOnly.contains("<tool_call>"), "tool markers should survive");

        String toolOnly = new StubAdapter(
                "{\"filter_thinking\":false,\"filter_tool_messages\":true}").filter(RAW);
        assertTrue(toolOnly.contains("<think>"), "thinking should survive");
        assertTrue(!toolOnly.contains("<tool_call>"), "tool markers should be stripped");
    }

    @Test
    @DisplayName("Blank / null input yields an empty string, never NPE")
    void blankInputIsSafe() {
        StubAdapter adapter = new StubAdapter("{}");
        assertEquals("", adapter.filter(null));
        assertEquals("", adapter.filter("   "));
    }
}
