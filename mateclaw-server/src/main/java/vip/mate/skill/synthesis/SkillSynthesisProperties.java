package vip.mate.skill.synthesis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RFC-023: Skill 自动合成配置
 *
 * @author SnSclaw
 */
@Data
@ConfigurationProperties(prefix = "mate.skill.synthesis")
public class SkillSynthesisProperties {

    /** 是否启用后台合成建议（对话结束后推 toast） */
    private boolean suggestEnabled = true;

    /** 触发建议的最小对话消息数 */
    private int minMessageCount = 10;

    /** 触发建议的最小工具调用数（从 metadata 统计） */
    private int minToolCallCount = 5;

    /** 合成用的模型 ID（null = 跟随系统默认模型） */
    private String modelId;
}
