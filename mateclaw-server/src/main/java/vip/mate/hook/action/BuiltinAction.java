package vip.mate.hook.action;

import lombok.extern.slf4j.Slf4j;
import vip.mate.hook.event.MateHookEvent;

/**
 * 内置动作：直接调 SnSclaw 内部 service，无外部 IO。
 *
 * <p>当前支持的 op（持续扩展）：
 * <ul>
 *   <li>{@code log.info} / {@code log.warn} —— 仅记日志</li>
 *   <li>{@code audit.append} —— 写 mate_audit_event（M3 落地）</li>
 *   <li>{@code metrics.increment} —— Micrometer counter（M3 落地）</li>
 * </ul></p>
 *
 * <p>因此本 action 零阻塞 IO、始终 < 1ms，是 hook 系统最便宜的实现。</p>
 */
@Slf4j
public non-sealed class BuiltinAction implements HookAction {

    private final String op;
    private final String arg;

    public BuiltinAction(String op, String arg) {
        this.op = op == null ? "log.info" : op;
        this.arg = arg == null ? "" : arg;
    }

    @Override
    public Kind kind() { return Kind.BUILTIN; }

    @Override
    public HookResult execute(MateHookEvent event, HookContext ctx) {
        long start = System.nanoTime();
        try {
            switch (op) {
                case "log.info"  -> log.info("[hook:{}] {} event={} payload={}",
                        ctx.hookName(), arg, event.type(), event.payload());
                case "log.warn"  -> log.warn("[hook:{}] {} event={} payload={}",
                        ctx.hookName(), arg, event.type(), event.payload());
                case "log.debug" -> log.debug("[hook:{}] {} event={} payload={}",
                        ctx.hookName(), arg, event.type(), event.payload());
                default -> {
                    // 未识别 op 不应静默成功；标记失败但失败隔离
                    return HookResult.failed("unknown builtin op: " + op,
                            (System.nanoTime() - start) / 1_000_000L);
                }
            }
            return HookResult.success(op, (System.nanoTime() - start) / 1_000_000L);
        } catch (Exception e) {
            return HookResult.failed(e.getMessage(), (System.nanoTime() - start) / 1_000_000L);
        }
    }

    @Override
    public long timeoutMillis() { return 100L; }   // builtin 不该超过 100ms
}
