package vip.mate.tool.disclosure;

import vip.mate.agent.context.TokenEstimator;

/** Fits a discovery catalog without cutting rows or tool invocation instructions. */
public final class ToolCatalogBudget {
    private ToolCatalogBudget() {}

    public static String fit(String catalog, int maxTokens) {
        if (catalog == null || catalog.isBlank() || maxTokens <= 0) return "";
        if (maxTokens == Integer.MAX_VALUE || TokenEstimator.estimateTokens(catalog) <= maxTokens) return catalog;
        String fallback = "\nDeferred tools: use tool_search to discover and tool_call to execute them.\n";
        StringBuilder fitted = new StringBuilder();
        boolean tableStarted = false;
        for (String line : catalog.split("\\n")) {
            if (line.startsWith("|")) tableStarted = true;
            String candidate = fitted + line + "\n";
            if (TokenEstimator.estimateTokens(candidate) > maxTokens) {
                if (!tableStarted) {
                    return TokenEstimator.estimateTokens(fallback) <= maxTokens ? fallback : "";
                }
                break;
            }
            fitted.append(line).append('\n');
        }
        String notice = "\nCatalog limited by budget; use tool_search for other scoped tools.\n";
        if (TokenEstimator.estimateTokens(fitted + notice) <= maxTokens) fitted.append(notice);
        return fitted.toString();
    }
}
