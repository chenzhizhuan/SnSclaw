package vip.mate.agent;

/**
 * Agent 运行状态枚举
 *
 * @author SnSclaw Team
 */
public enum AgentState {

    /** 空闲，等待任务 */
    IDLE,

    /** 规划中，正在生成执行计划 */
    PLANNING,

    /** 执行中，正在执行工具调用或子任务 */
    EXECUTING,

    /** 运行中（ReAct / PlanExecute 使用） */
    RUNNING,

    /** 等待用户输入 */
    WAITING_USER_INPUT,

    /** 已完成 */
    DONE,

    /** 执行失败 */
    FAILED,

    /** 错误状态（流式调用异常） */
    ERROR
}
