package vip.mate.agent.runtime.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;

import reactor.core.publisher.Mono;

import java.util.List;

@FunctionalInterface
public interface GuardedToolBatchExecutor {
    Mono<GuardedToolBatchResult> execute(List<ToolUseBlock> calls, RuntimeContext context);
}
