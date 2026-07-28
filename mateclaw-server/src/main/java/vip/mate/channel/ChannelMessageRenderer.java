package vip.mate.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 渠道消息渲染器
 * <p>
 * 渠道消息渲染设计：
 * - 过滤 thinking 标签和工具调用信息
 * - 按平台字数限制分割长消息
 * - 保持代码块完整性（不在 ``` 中间切割）
 *
 * @author SnSclaw Team
 */
public final class ChannelMessageRenderer {

    private ChannelMessageRenderer() {}

    /** 各平台消息字数限制 */
    public static final Map<String, Integer> PLATFORM_LIMITS = Map.of(
            "telegram", 4096,
            "discord", 2000,
            "dingtalk", 20000,
            "feishu", 10000,
            "wecom", 2048,
            "qq", 4096,
            "weixin", 4096
    );

    /** 匹配 <think>...</think> 标签（含嵌套内容） */
    private static final Pattern THINK_PATTERN = Pattern.compile(
            "<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);

    /** 匹配 <tool_call>...</tool_call> */
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "<tool_call>[\\s\\S]*?</tool_call>", Pattern.CASE_INSENSITIVE);

    /** 匹配 <tool_result>...</tool_result> */
    private static final Pattern TOOL_RESULT_PATTERN = Pattern.compile(
            "<tool_result>[\\s\\S]*?</tool_result>", Pattern.CASE_INSENSITIVE);

    /** 匹配 ReAct 格式的中间步骤行：Action: / Action Input: / Observation: */
    private static final Pattern REACT_STEP_PATTERN = Pattern.compile(
            "(?m)^(Action|Action Input|Observation):.*$");

    /** 代码块围栏标记 */
    private static final String CODE_FENCE = "```";

    /** 代码块围栏最大额外开销：开启 "```lang\n" + 关闭 "\n```" ≈ 最长语言标识20字符 + 固定8字符 */
    private static final int CODE_FENCE_OVERHEAD = 30;

    /** 分割时的安全余量（为代码块关闭/开启标记留空间） */
    private static final int SAFETY_MARGIN = 100 + CODE_FENCE_OVERHEAD;

    // ==================== 核心 API ====================

    /**
     * 综合渲染：过滤 + 分割
     *
     * @param content             原始内容
     * @param filterThinking      是否过滤 thinking 标签
     * @param filterToolMessages  是否过滤工具调用信息
     * @param messageFormat       消息格式（暂留扩展，当前不做转换）
     * @param maxLength           平台字数限制
     * @return 分割后的消息段列表
     */
    public static List<String> renderForChannel(String content,
                                                 boolean filterThinking,
                                                 boolean filterToolMessages,
                                                 String messageFormat,
                                                 int maxLength) {
        if (content == null || content.isBlank()) {
            return List.of("");
        }

        String rendered = content;

        // 1. 过滤 thinking
        if (filterThinking) {
            rendered = stripThinking(rendered);
        }

        // 2. 过滤工具调用
        if (filterToolMessages) {
            rendered = stripToolCalls(rendered);
        }

        // 3. 清理多余空行
        rendered = rendered.replaceAll("\n{3,}", "\n\n").trim();

        if (rendered.isEmpty()) {
            return List.of("");
        }

        // 4. 按平台限制分割
        return truncateForPlatform(rendered, maxLength);
    }

    // ==================== 过滤方法 ====================

    /**
     * 移除 &lt;think&gt;...&lt;/think&gt; 标签及其内容
     */
    public static String stripThinking(String content) {
        if (content == null) return "";
        return THINK_PATTERN.matcher(content).replaceAll("").trim();
    }

    /**
     * 移除工具调用信息：
     * - &lt;tool_call&gt;...&lt;/tool_call&gt;
     * - &lt;tool_result&gt;...&lt;/tool_result&gt;
     * - Action: / Action Input: / Observation: 行（ReAct 格式）
     */
    public static String stripToolCalls(String content) {
        if (content == null) return "";
        String result = content;
        result = TOOL_CALL_PATTERN.matcher(result).replaceAll("");
        result = TOOL_RESULT_PATTERN.matcher(result).replaceAll("");
        result = REACT_STEP_PATTERN.matcher(result).replaceAll("");
        return result.trim();
    }

    // ==================== 分割方法 ====================

    /**
     * 按平台字数限制分割消息，保持代码块完整性
     *
     * @param content   内容
     * @param maxLength 最大长度
     * @return 分割后的消息段列表
     */
    public static List<String> truncateForPlatform(String content, int maxLength) {
        if (content == null || content.isEmpty()) {
            return List.of("");
        }

        if (content.length() <= maxLength) {
            return List.of(content);
        }

        List<String> segments = new ArrayList<>();
        int effectiveMax = maxLength - SAFETY_MARGIN;
        if (effectiveMax <= 0) {
            effectiveMax = maxLength;
        }

        int pos = 0;
        boolean inCodeBlock = false;
        String codeBlockLang = ""; // 记录代码块语言标识

        while (pos < content.length()) {
            int remaining = content.length() - pos;
            if (remaining <= maxLength) {
                // 剩余内容不超限，直接作为最后一段
                String lastSegment = content.substring(pos);
                if (inCodeBlock) {
                    lastSegment = CODE_FENCE + codeBlockLang + "\n" + lastSegment;
                }
                segments.add(lastSegment);
                break;
            }

            // 在 effectiveMax 范围内寻找最佳切割点
            int cutPoint = findCutPoint(content, pos, effectiveMax);
            String chunk = content.substring(pos, cutPoint);

            // 如果上一段结束时在代码块内，本段开头需要重新打开
            if (inCodeBlock) {
                chunk = CODE_FENCE + codeBlockLang + "\n" + chunk;
            }

            // 统计本段中的代码块围栏数量，更新状态
            CodeBlockState state = analyzeCodeFences(chunk, inCodeBlock, codeBlockLang);
            inCodeBlock = state.inCodeBlock;
            codeBlockLang = state.lang;

            // 如果本段结束时仍在代码块内，需要关闭
            if (inCodeBlock) {
                chunk = chunk + "\n" + CODE_FENCE;
            }

            segments.add(chunk);
            pos = cutPoint;
        }

        return segments;
    }

    // ==================== 内部辅助 ====================

    /**
     * 在 [start, start + maxLen] 范围内寻找最佳切割点
     * 优先在换行符处切割；如果找不到，硬切
     */
    private static int findCutPoint(String content, int start, int maxLen) {
        int end = Math.min(start + maxLen, content.length());

        // 从 end 往前找最近的换行符
        for (int i = end - 1; i > start + maxLen / 2; i--) {
            if (content.charAt(i) == '\n') {
                return i + 1; // 包含换行符
            }
        }

        // 找不到合适的换行符，硬切
        return end;
    }

    /**
     * 分析文本中的代码块围栏，返回结束时的状态
     */
    private static CodeBlockState analyzeCodeFences(String text, boolean initiallyInBlock, String initialLang) {
        boolean inBlock = initiallyInBlock;
        String lang = initialLang;
        int idx = 0;

        while (idx < text.length()) {
            int fencePos = text.indexOf(CODE_FENCE, idx);
            if (fencePos == -1) break;

            if (!inBlock) {
                // 进入代码块，尝试提取语言标识
                int lineEnd = text.indexOf('\n', fencePos);
                if (lineEnd == -1) lineEnd = text.length();
                lang = text.substring(fencePos + CODE_FENCE.length(), lineEnd).trim();
                if (!lang.isEmpty() && !lang.matches("[a-zA-Z0-9+#_.-]+")) {
                    lang = ""; // 无效的语言标识
                }
                inBlock = true;
            } else {
                // 退出代码块
                inBlock = false;
                lang = "";
            }

            idx = fencePos + CODE_FENCE.length();
        }

        return new CodeBlockState(inBlock, lang);
    }

    private record CodeBlockState(boolean inCodeBlock, String lang) {}
}
