package vip.mate.tool.mcp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.tool.mcp.service.McpServerService;

/**
 * MCP Server 启动初始化
 * <p>
 * 在 Spring Boot 启动完成后，自动连接所有已启用的 MCP server。
 * 单个 server 连接失败不影响其他 server 或应用启动。
 *
 * @author SnSclaw
 */
@Slf4j
@Component
@Order(200) // 在 DatabaseBootstrapRunner 之后执行
@RequiredArgsConstructor
public class McpServerBootstrapRunner implements ApplicationRunner {

    private final McpServerService mcpServerService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            mcpServerService.initEnabledServers();
        } catch (Exception e) {
            // 整体初始化失败也不阻塞启动
            log.error("MCP server initialization failed (non-fatal): {}", e.getMessage(), e);
        }
    }
}
