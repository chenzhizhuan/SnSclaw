package vip.mate.tool.builtin;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.chat.model.ToolContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxRenderToolReturnDirectTest {

    @Test
    void docxRenderToolsReturnGeneratedFileDirectly() throws Exception {
        assertReturnDirect("renderDocx", String.class, String.class, String.class, ToolContext.class);
        assertReturnDirect("renderDocxFromFile", String.class, String.class, String.class, ToolContext.class);
        assertReturnDirect("renderDocxFromFiles", java.util.List.class, String.class, String.class, ToolContext.class);
    }

    private static void assertReturnDirect(String methodName, Class<?>... parameterTypes) throws Exception {
        Tool tool = DocxRenderTool.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(Tool.class);

        assertTrue(tool.returnDirect(), methodName + " must stop the tool loop after producing a download link");
    }
}
