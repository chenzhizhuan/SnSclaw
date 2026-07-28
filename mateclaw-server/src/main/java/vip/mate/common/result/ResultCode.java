package vip.mate.common.result;

import lombok.Getter;

/**
 * 响应状态码枚举
 *
 * @author SnSclaw Team
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "result.success"),
    UNAUTHORIZED(401, "result.unauthorized"),
    FORBIDDEN(403, "result.forbidden"),
    NOT_FOUND(404, "result.not_found"),
    SYSTEM_ERROR(500, "result.system_error"),
    PARAM_ERROR(400, "result.param_error"),
    AGENT_NOT_FOUND(1001, "result.agent_not_found"),
    AGENT_BUSY(1002, "result.agent_busy"),
    LLM_ERROR(2001, "result.llm_error"),
    TOOL_NOT_FOUND(3001, "result.tool_not_found"),
    CHANNEL_ERROR(4001, "result.channel_error");

    private final int code;
    /** i18n message key */
    private final String msgKey;

    ResultCode(int code, String msgKey) {
        this.code = code;
        this.msgKey = msgKey;
    }

    /**
     * 获取本地化消息（需要 I18nService 实例）
     */
    public String getMsg(vip.mate.i18n.I18nService i18n) {
        return i18n != null ? i18n.msg(msgKey) : msgKey;
    }

    /**
     * 获取消息 key（向后兼容）
     */
    public String getMsg() {
        return msgKey;
    }
}
