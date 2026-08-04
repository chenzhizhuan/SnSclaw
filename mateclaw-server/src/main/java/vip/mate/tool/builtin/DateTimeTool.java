package vip.mate.tool.builtin;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 内置工具：日期时间
 *
 * @author SnSclaw
 */
@Component
public class DateTimeTool {

    @Tool(description = "Get current date and time in yyyy-MM-dd HH:mm:ss format")
    public String getCurrentDateTime() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool(description = "Get current date in yyyy-MM-dd format")
    public String getCurrentDate() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    @Tool(description = "Get current time in HH:mm:ss format")
    public String getCurrentTime() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}
