package vip.mate.plugin.sample;

import org.slf4j.Logger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginContext;

/**
 * Sample plugin demonstrating the SnSclaw Plugin SDK.
 * <p>
 * Registers a simple "hello_world" tool that agents can call.
 *
 * @author SnSclaw Team
 */
public class HelloPlugin implements MateClawPlugin {

    private Logger log;

    @Override
    public void onLoad(PluginContext context) {
        this.log = context.getLogger();
        log.info("HelloPlugin loading...");

        // Register tool callbacks from this class's @Tool methods
        ToolCallback[] callbacks = ToolCallbacks.from(this);
        for (ToolCallback callback : callbacks) {
            context.registerTool(callback);
        }

        log.info("HelloPlugin loaded, registered {} tools", callbacks.length);
    }

    @Override
    public void onEnable() {
        if (log != null) log.info("HelloPlugin enabled");
    }

    @Override
    public void onDisable() {
        if (log != null) log.info("HelloPlugin disabled");
    }

    @Tool(description = "A greeting tool from the Hello World plugin. Returns a friendly greeting message for the given name.")
    public String hello_world(
            @ToolParam(description = "The name to greet") String name) {
        return "Hello, " + name + "! This message comes from the SnSclaw Hello World plugin.";
    }
}
