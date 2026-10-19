package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies provider-first reasoning effort policy against the final JSON request. */
class ReasoningEffortSanitizerTest {

    private static ModelProviderEntity provider(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        return p;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode request(String model, String reasoningEffort) {
        ObjectNode request = JSON.createObjectNode().put("model", model);
        request.putArray("messages");
        if (reasoningEffort != null) request.put("reasoning_effort", reasoningEffort);
        return request;
    }

    private static ObjectNode sanitize(ObjectNode request, ModelProviderEntity provider) {
        return OpenAiRequestRewriter.rewrite(request, provider, false);
    }

    private static boolean isAllowed(ModelProviderEntity provider) {
        return sanitize(request("gpt-5", "high"), provider).has("reasoning_effort");
    }

    @Test
    @DisplayName("Whitelist: openai is allowed")
    void whitelist_openai() {
        assertTrue(isAllowed(provider("openai")));
    }

    @Test
    @DisplayName("Whitelist: azure-openai is allowed")
    void whitelist_azureOpenai() {
        assertTrue(isAllowed(provider("azure-openai")));
    }

    @Test
    @DisplayName("Whitelist: case-insensitive (Azure-OpenAI)")
    void whitelist_caseInsensitive() {
        assertTrue(isAllowed(provider("Azure-OpenAI")));
    }

    @Test
    @DisplayName("Whitelist: deepseek is denied")
    void denylist_deepseek() {
        assertFalse(isAllowed(provider("deepseek")));
    }

    @Test
    @DisplayName("Whitelist: kimi family denied")
    void denylist_kimi() {
        assertFalse(isAllowed(provider("kimi-cn")));
        assertFalse(isAllowed(provider("kimi-intl")));
        assertFalse(isAllowed(provider("kimi-code")));
    }

    @Test
    @DisplayName("Whitelist: dashscope / ollama / anthropic denied")
    void denylist_misc() {
        assertFalse(isAllowed(provider("dashscope")));
        assertFalse(isAllowed(provider("ollama")));
        assertFalse(isAllowed(provider("anthropic")));
    }

    @Test
    @DisplayName("Whitelist: unknown providerId denied (default-deny — §5.2 Case E3.3)")
    void denylist_unknownProvider() {
        // This is the critical regression guard: if anyone re-adds a default-allow
        // branch to isReasoningEffortWhitelistedProvider, this case fails first.
        assertFalse(isAllowed(
                provider("my-custom-openai-compat-gateway")));
        assertFalse(isAllowed(
                provider("openrouter")));
        assertFalse(isAllowed(
                provider("together")));
    }

    @Test
    @DisplayName("Whitelist: null provider / null providerId denied")
    void denylist_nulls() {
        assertFalse(isAllowed(null));
        assertFalse(isAllowed(new ModelProviderEntity()));
    }

    // ---------- sanitizeReasoningEffortForProvider ----------

    @Test
    @DisplayName("Sanitize no-op: request has no reasoning_effort")
    void sanitize_noop_noReasoningEffort() {
        ObjectNode req = request("gpt-5", null);
        ObjectNode out = sanitize(req, provider("deepseek"));
        assertEquals(req, out, "should return same JSON content when reasoning_effort is already absent");
    }

    @Test
    @DisplayName("§5.2 Case E3.1: primary=gpt-5 → fallback=deepseek strips reasoning_effort")
    void sanitize_failover_deepseek_strips() {
        // Simulate failover: OpenAiChatOptions.model still leaked as "gpt-5" on the deepseek request.
        ObjectNode req = request("gpt-5", "high");
        ObjectNode out = sanitize(req, provider("deepseek"));
        assertNull(out.path("reasoning_effort").asText(null), "deepseek is not on the whitelist — strip regardless of model name");
        // Other fields preserved
        assertEquals("gpt-5", out.path("model").asText(null));
    }

    @Test
    @DisplayName("§5.2 Case E3.2: kimi / dashscope / ollama also strip")
    void sanitize_failover_otherDenied_strips() {
        for (String pid : List.of("kimi-cn", "kimi-intl", "kimi-code", "dashscope", "ollama", "anthropic")) {
            ObjectNode req = request("gpt-5", "medium");
            ObjectNode out = sanitize(req, provider(pid));
            assertNull(out.path("reasoning_effort").asText(null), "provider=" + pid + " must strip");
        }
    }

    @Test
    @DisplayName("§5.2 Case E3.3: unknown provider strips (default-deny regression guard)")
    void sanitize_unknownProvider_strips() {
        ObjectNode req = request("gpt-5", "high");
        ObjectNode out = sanitize(
                req, provider("my-custom-openai-compat-gateway"));
        assertNull(out.path("reasoning_effort").asText(null),
                "unknown provider must strip (default-deny) — if this fails, someone re-added default-allow");
    }

    @Test
    @DisplayName("Whitelist + supporting model: keep reasoning_effort (gpt-5 on openai)")
    void sanitize_whitelisted_supportingModel_keeps() {
        ObjectNode req = request("gpt-5", "high");
        ObjectNode out = sanitize(req, provider("openai"));
        assertEquals(req, out, "gpt-5 on openai should pass through unchanged");
        assertEquals("high", out.path("reasoning_effort").asText(null));
    }

    @Test
    @DisplayName("Whitelist + non-supporting model: strip (gpt-4 on openai)")
    void sanitize_whitelisted_nonSupportingModel_strips() {
        // gpt-4 is NOT OPENAI_REASONING family — reasoning_effort is not applicable there.
        ObjectNode req = request("gpt-4", "medium");
        ObjectNode out = sanitize(req, provider("openai"));
        assertNull(out.path("reasoning_effort").asText(null),
                "gpt-4 is whitelisted-provider but non-supporting-family — family gate should strip");
    }

    @Test
    @DisplayName("Azure OpenAI with supporting model: keep reasoning_effort")
    void sanitize_azureOpenai_supporting_keeps() {
        ObjectNode req = request("gpt-5", "low");
        ObjectNode out = sanitize(req, provider("azure-openai"));
        assertEquals("low", out.path("reasoning_effort").asText(null));
    }

    @Test
    @DisplayName("Null provider: strip (defensive)")
    void sanitize_nullProvider_strips() {
        ObjectNode req = request("gpt-5", "high");
        ObjectNode out = sanitize(req, null);
        assertNull(out.path("reasoning_effort").asText(null));
    }
}
