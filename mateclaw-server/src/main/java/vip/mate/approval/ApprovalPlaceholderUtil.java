package vip.mate.approval;

/**
 * 审批占位消息检测工具（共享单点定义）
 * <p>
 * 实现 TOOL_GUARD_DENIED_MARK 语义：
 * 检测 assistant 消息内容是否为审批占位文本，用于：
 * <ul>
 *   <li>BaseAgent.buildConversationHistory() — 运行时过滤，防止 LLM 看到审批残留</li>
 *   <li>ConversationService.removeApprovalPlaceholders() — DB 物理清理</li>
 * </ul>
 *
 * @author SnSclaw
 */
public final class ApprovalPlaceholderUtil {

    private ApprovalPlaceholderUtil() {
    }

    /**
     * 判断消息内容是否为审批占位消息
     */
    public static boolean isApprovalPlaceholder(String content) {
        if (content == null || content.isEmpty()) return false;
        return content.contains("[⏳ 等待审批]")
                || content.contains("[APPROVAL_PENDING]")
                || content.contains("[等待审批]")
                || content.contains("请输入 /approve")
                || content.contains("等待您的批准");
    }
}
