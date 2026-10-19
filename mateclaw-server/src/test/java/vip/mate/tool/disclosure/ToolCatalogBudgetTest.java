package vip.mate.tool.disclosure;

import org.junit.jupiter.api.Test;
import vip.mate.agent.context.TokenEstimator;
import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogBudgetTest {
    @Test
    void fitsCompleteRowsAndKeepsDiscoveryInstructions() {
        String header = "## Extension Tools\nUse tool_search and tool_call to find deferred tools.\n| Tool | Required |\n|---|---|\n";
        String catalog = header + "| tool_one | language:string python bash node |\n".repeat(40);
        String fitted = ToolCatalogBudget.fit(catalog, 110);
        assertTrue(TokenEstimator.estimateTokens(fitted) <= 110);
        assertTrue(fitted.contains("tool_search"));
        assertTrue(fitted.lines().filter(line -> line.startsWith("| ")).allMatch(line -> line.endsWith(" |")));
    }
    @Test
    void handlesZeroAndDisabledBudgets() {
        assertEquals("", ToolCatalogBudget.fit("too much", 0));
        assertEquals("catalog", ToolCatalogBudget.fit("catalog", Integer.MAX_VALUE));
    }
}
