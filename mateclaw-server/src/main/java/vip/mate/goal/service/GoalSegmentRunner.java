package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.GoalContinuationContext;
import vip.mate.agent.runtime.ConversationTurnGate;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.AgentStreamAccumulator;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.GoalEntity;
import vip.mate.workspace.conversation.ConversationService;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/** Runs and persists one ordinary graph segment without an HTTP subscriber. */
@Component
public class GoalSegmentRunner {
    private final AgentService agents;
    private final ConversationService conversations;
    private final ApprovalWorkflowService approvals;
    private final ChatStreamTracker streams;
    private final ObjectMapper mapper;
    private final ConversationTurnGate gate;
    private final ConcurrentHashMap<Long,Worker> workers=new ConcurrentHashMap<>();
    private volatile boolean closing;
    private static final class Worker {
        final String conversationId;
        final AtomicBoolean cancelled=new AtomicBoolean();
        final AtomicBoolean interrupted=new AtomicBoolean();
        final AtomicReference<ChatStreamTracker.RunHandle> handle=new AtomicReference<>();
        volatile boolean interactive;
        Worker(String conversationId) { this.conversationId=conversationId; }
    }
    @org.springframework.beans.factory.annotation.Autowired
    private GoalService goals;

    public GoalSegmentRunner(AgentService agents, ConversationService conversations,
            ApprovalWorkflowService approvals, ChatStreamTracker streams, ObjectMapper mapper, ConversationTurnGate gate) {
        this.agents=agents;this.conversations=conversations;this.approvals=approvals;
        this.streams=streams;this.mapper=mapper;this.gate=gate;
    }

    public record Result(String finishReason, boolean awaitingApproval, boolean evaluationUnavailable) {
        public Result(String finishReason, boolean awaitingApproval) { this(finishReason,awaitingApproval,false); }
    }

    /** Cancel this worker only, never a newer conversation generation or a user turn. */
    public void cancel(Long goalId) {
        Worker worker=workers.get(goalId);
        if (worker!=null && !worker.interactive) {
            cancelWorker(worker);
        }
    }

    /** User Stop applies even after the goal completed and queued interactive work took over. */
    public void stopConversation(String conversationId) {
        workers.values().stream().filter(w -> Objects.equals(w.conversationId,conversationId)).forEach(this::cancelWorker);
    }

    public void cancelAll() {
        closing=true;
        workers.values().forEach(this::cancelWorker);
    }

    private void cancelWorker(Worker worker) {
        worker.cancelled.set(true);
        streams.cancelRun(worker.handle.get());
        // Stream disposal releases the completion latch. Never interrupt this
        // worker: it also performs JDBC I/O on shared embedded database channels.
    }

    public Result run(GoalEntity goal, String prompt, boolean recovered) {
        String convId=goal.getConversationId();
        var permit=gate.tryAcquire(convId);
        if (permit==null) throw new MateClawException("err.agent.conversation_busy",409,"Conversation is busy");
        Worker worker=new Worker(convId);
        try {
            workers.put(goal.getId(),worker);
            // Register before checking the shutdown fence so cancellation cannot miss us.
            if (closing) {
                worker.cancelled.set(true);
                return new Result("stopped",false);
            }
            var conv=conversations.findByConversationId(convId);
            if (conv==null || !Objects.equals(conv.getWorkspaceId(),goal.getWorkspaceId())
                    || !Objects.equals(conv.getAgentId(),goal.getAgentId())
                    || !Objects.equals(conv.getUsername(),goal.getCreatedBy())
                    || Integer.valueOf(1).equals(conv.getDeleted()) || Integer.valueOf(1).equals(conv.getArchived())) {
                throw new IllegalStateException("Goal conversation identity changed or conversation unavailable");
            }
            var agent=agents.getAgent(goal.getAgentId());
            if (agent==null || Boolean.FALSE.equals(agent.getEnabled())
                    || (agent.getRuntimeType()!=null && !"native".equals(agent.getRuntimeType()))) {
                throw new IllegalStateException("Goal requires an enabled native runtime with goal evaluation");
            }
            if (approvals.findPendingByConversation(convId)!=null) return new Result("",true);
            if (streams.isRunning(convId)) {
                throw new MateClawException("err.agent.conversation_busy",409,"Conversation has pending input");
            }
            String guidance=recovered ? "The previous execution was interrupted by a runtime restart. "
                    + "Inspect the workspace, progress ledger and existing async handles before acting. "
                    + "Do not replay side effects whose outcome is unknown; request review if their outcome cannot be verified.\n" : "";
            ChatOrigin origin=ChatOrigin.web(convId,goal.getCreatedBy(),goal.getWorkspaceId(),null).withAgent(goal.getAgentId());
            Result result;
            ChatStreamTracker.QueuedInput queued=streams.consumeQueuedInput(convId);
            do {
                String input=guidance+prompt;
                if (queued!=null) {
                    worker.interactive=true;
                    if (!queued.persisted()) {
                        var saved=conversations.saveMessage(convId,"user",queued.message(),queued.contentParts(),"queued");
                        if (saved!=null) origin=origin.withOriginMessageId(saved.getId());
                    }
                    if (queued.agentId()!=null && !queued.agentId().equals(goal.getAgentId())) {
                        throw new IllegalStateException("Queued input targets a different agent; user review required");
                    }
                    input=queuedPrompt(queued);
                    streams.broadcastObject(convId,"queued_input_started",Map.of("conversationId",convId,"message",input));
                } else if (goals!=null && goals.getById(goal.getId()).getStatus()!=vip.mate.goal.model.GoalStatus.ACTIVE) {
                    return new Result("stopped",false);
                }
                result=runSegment(goal,input,origin,permit,worker);
                if (result.awaitingApproval() || "stopped".equals(result.finishReason())) return result;
                queued=streams.consumeQueuedInput(convId);
            } while (queued!=null);
            return result;
        } catch (RuntimeException error) {
            if (Thread.interrupted()) worker.interrupted.set(true);
            // Accepted user input must survive even when this goal cannot continue.
            ChatStreamTracker.QueuedInput pending;
            while ((pending=streams.consumeQueuedInput(convId))!=null) {
                if (!pending.persisted()) conversations.saveMessage(convId,"user",pending.message(),pending.contentParts(),"queued");
            }
            streams.broadcastObject(convId,"warning",Map.of("message",
                    "Goal execution interrupted. Queued input was saved; review the execution state before resuming."));
            throw error;
        } finally {
            try {
                // Cooperative cancellation returns normally, bypassing the error path.
                // Accepted input must still survive process exit, including attachments.
                if (worker.cancelled.get()) {
                    ChatStreamTracker.QueuedInput pending;
                    while ((pending=streams.consumeQueuedInput(convId))!=null) {
                        if (!pending.persisted()) conversations.saveMessage(convId,"user",pending.message(),pending.contentParts(),"queued");
                    }
                }
            } finally {
                workers.remove(goal.getId(),worker);
                permit.close();
                if (worker.interrupted.get()) Thread.currentThread().interrupt();
            }
        }
    }

    private Result runSegment(GoalEntity goal, String input, ChatOrigin origin, ConversationTurnGate.Permit permit, Worker worker) {
        String convId=goal.getConversationId();
        var handle=streams.register(convId);
        worker.handle.set(handle);
        streams.incrementFlux(convId);
        AgentStreamAccumulator accumulator=new AgentStreamAccumulator(mapper,new AgentStreamAccumulator.Sink() {
            @Override public void broadcast(String id,String name,Object payload) { streams.broadcastObject(id,name,payload); }
            @Override public void updatePhase(String id,String phase) { streams.updatePhase(id,phase); }
        });
        AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicBoolean evaluationUnavailable=new AtomicBoolean();
        AtomicBoolean persisted=new AtomicBoolean();
        CountDownLatch finished=new CountDownLatch(1);
        Disposable subscription=null;
        try {
            if (worker.cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            conversations.updateStreamStatus(convId,"running");
            streams.broadcastObject(convId,"message_start",Map.of("role","assistant","trigger","goal"));
            subscription=gate.withPermit(permit,() -> GoalContinuationContext.call(!worker.interactive, () ->
                    reactor.core.publisher.Flux.defer(() -> {
                        if (worker.cancelled.get()) return reactor.core.publisher.Flux.empty();
                        return agents.chatStructuredStream(goal.getAgentId(),input,
                            convId,goal.getCreatedBy(),null,origin)
                    .doOnNext(delta -> {
                        accumulator.accept(delta,convId);
                        if ("goal_evaluated".equals(delta.eventType()) && delta.eventData()!=null
                                && (Boolean.TRUE.equals(delta.eventData().get("skipped"))
                                || "fallback".equals(delta.eventData().get("decision")))) evaluationUnavailable.set(true);
                    });
                    })
                    .doOnSubscribe(s -> streams.setDisposable(handle, s::cancel))
                    .doFinally(signal -> finished.countDown())
                    .subscribe(delta -> {},failure::set)));
            streams.setDisposable(handle,subscription);
            finished.await();
            String reason=streams.isStopRequested(convId)
                    ? streams.getInterruptType(convId)==ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP
                        ? "interrupted" : "stopped"
                    : accumulator.getFinishReason();
            String status="stopped".equals(reason) ? "stopped" : "interrupted".equals(reason) ? "interrupted" : accumulator.isAwaitingApproval()
                    ? "awaiting_approval" : failure.get()!=null || "error_fallback".equals(reason) ? "error" : "completed";
            persist(convId,accumulator,status);
            persisted.set(true);
            streams.broadcastObject(convId,"message_complete",Map.of("status",status,"trigger","goal"));
            if (failure.get()!=null && !"stopped".equals(reason)) {
                throw failure.get() instanceof RuntimeException runtime ? runtime : new RuntimeException(failure.get());
            }
            return new Result(reason,accumulator.isAwaitingApproval(),evaluationUnavailable.get());
        } catch (InterruptedException interrupted) {
            worker.interrupted.set(true);
            throw new IllegalStateException("Goal worker interrupted; recover from persisted evidence",interrupted);
        } finally {
            if (Thread.interrupted()) worker.interrupted.set(true);
            if (worker.cancelled.get() || worker.interrupted.get()) streams.cancelRun(handle);
            if (subscription!=null) subscription.dispose();
            try {
                if (!persisted.get()) persist(convId,accumulator,"interrupted");
            } finally {
                conversations.updateStreamStatus(convId,"idle");
                streams.broadcastObject(convId,"done",Map.of("status","segment_finished"));
                streams.complete(handle);
            }
        }
    }

    private void persist(String convId,AgentStreamAccumulator accumulator,String status) {
        conversations.saveMessage(convId,"assistant",accumulator.getContent(),accumulator.toAssistantParts(),status,
                accumulator.getPromptTokens(),accumulator.getCompletionTokens(),accumulator.getCacheReadTokens(),
                accumulator.getCacheWriteTokens(),accumulator.getReasoningTokens(),accumulator.getRuntimeModelName(),
                accumulator.getRuntimeProviderId(),accumulator.toMetadataJson());
    }

    private String queuedPrompt(ChatStreamTracker.QueuedInput queued) {
        if (queued.contentParts()==null || queued.contentParts().isEmpty()) return queued.message();
        var message=new vip.mate.workspace.conversation.model.MessageEntity();
        message.setContent(queued.message());
        try { message.setContentParts(mapper.writeValueAsString(queued.contentParts())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("Invalid queued input",error); }
        return conversations.renderMessageContent(message,true);
    }
}
