package vip.mate.agent.graph;

import org.springframework.ai.chat.messages.*;
import java.util.*;

/** Caller-owned, model-free recovery for serialized request size rejection. */
public final class PayloadReduction {
    private PayloadReduction() {}
    public static final String INCOMPLETE = "任务未完成：请求体过大（HTTP 413），无法通过缩减历史恢复。请拆分任务或附件，或减少启用的工具后重试；若仍失败，请检查代理或模型服务的请求大小限制。";

    /** Returns a strict subsequence, or null if mandatory input cannot safely shrink. */
    public static List<Message> reduce(List<Message> messages) {
        if (messages == null || messages.size() <= 4) return null;
        int cut = messages.size() - 4;
        // Move the tail boundary back across every crossing tool pair. Repeat
        // because moving it can expose another pair in a parallel tool batch.
        for (int i = 0; i < cut; i++) {
            if (messages.get(i) instanceof AssistantMessage assistant && !assistant.getMedia().isEmpty()) {
                cut = i;
                break;
            }
        }
        boolean changed;
        do {
            changed = false;
            Set<String> tailResponses = new HashSet<>();
            for (int i = cut; i < messages.size(); i++) {
                if (messages.get(i) instanceof ToolResponseMessage response)
                    response.getResponses().forEach(r -> tailResponses.add(r.id()));
            }
            for (int i = 0; i < cut; i++) {
                if (messages.get(i) instanceof AssistantMessage assistant
                        && assistant.getToolCalls().stream().anyMatch(t -> tailResponses.contains(t.id()))) {
                    cut = i;
                    changed = true;
                    break;
                }
            }
        } while (changed);
        Set<String> protectedIds = new HashSet<>();
        Set<String> mandatoryTools = Set.of("load_skill", "delegateToAgent", "delegateParallel");
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage response)
                response.getResponses().stream().filter(r -> mandatoryTools.contains(r.name()))
                        .forEach(r -> protectedIds.add(r.id()));
        }
        // A multi-tool assistant or response is indivisible. Close the protected
        // set over the whole batch so preserving a skill never creates orphans.
        int previousSize;
        do {
            previousSize = protectedIds.size();
            for (Message message : messages) {
                if (message instanceof AssistantMessage assistant
                        && assistant.getToolCalls().stream().anyMatch(t -> protectedIds.contains(t.id())))
                    assistant.getToolCalls().forEach(t -> protectedIds.add(t.id()));
                if (message instanceof ToolResponseMessage response
                        && response.getResponses().stream().anyMatch(r -> protectedIds.contains(r.id())))
                    response.getResponses().forEach(r -> protectedIds.add(r.id()));
            }
        } while (protectedIds.size() > previousSize);
        List<Message> reduced = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            // Preserve every user and system message, including original goal,
            // loaded skills and media. Never truncate a mandatory message body.
            if (i >= cut || message instanceof SystemMessage || message instanceof UserMessage
                    || message instanceof AssistantMessage assistant
                        && assistant.getToolCalls().stream().anyMatch(t -> protectedIds.contains(t.id()))
                    || message instanceof ToolResponseMessage response
                        && response.getResponses().stream().anyMatch(r -> protectedIds.contains(r.id())))
                reduced.add(message);
        }
        return reduced.size() < messages.size() ? reduced : null;
    }

    public static NodeStreamingChatHelper.StreamResult incomplete(NodeStreamingChatHelper.StreamResult source) {
        return new NodeStreamingChatHelper.StreamResult(null, "", new AssistantMessage(INCOMPLETE),
                List.of(), false, source.promptTokens(), source.completionTokens(), false, INCOMPLETE,
                NodeStreamingChatHelper.ErrorType.PAYLOAD_TOO_LARGE, source.stopped(),
                source.cacheReadTokens(), source.cacheWriteTokens(), source.reasoningTokens());
    }
}
