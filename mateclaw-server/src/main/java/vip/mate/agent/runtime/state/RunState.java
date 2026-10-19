package vip.mate.agent.runtime.state;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.BeanUtils;

import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.goal.model.GoalEntity;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-run state with owned container, event payload, and message metadata copies. GoalEntity scalar
 * fields are copied. Other typed leaves (including media resources) retain identity and must be
 * treated as immutable by callers; no POJO serialization is performed.
 */
public final class RunState {
    private static final Set<String> APPEND_KEYS =
            Set.of(
                    MateClawStateKeys.MESSAGES,
                    MateClawStateKeys.PENDING_EVENTS,
                    PlanStateKeys.COMPLETED_RESULTS);
    private final Map<String, Object> values = new LinkedHashMap<>();

    public RunState() {
        this(Map.of());
    }

    public RunState(Map<String, Object> initial) {
        if (initial != null)
            initial.forEach(
                    (key, value) -> {
                        if (value != null) values.put(key, copy(value));
                    });
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> Optional<T> value(String key) {
        return Optional.ofNullable((T) copy(values.get(key)));
    }

    public synchronized <T> Optional<T> value(String key, Class<T> type) {
        Object value = copy(values.get(key));
        if (type == null) return value(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    public synchronized <T> T value(String key, T defaultValue) {
        return this.<T>value(key).orElse(defaultValue);
    }

    public synchronized Map<String, Object> data() {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, copy(value)));
        return Collections.unmodifiableMap(result);
    }

    /** History keys append; a null history patch is a no-op. Other keys replace. */
    public synchronized void applyPatch(Map<String, Object> patch) {
        if (patch == null) return;
        patch.forEach(
                (key, value) -> {
                    if (value == null) {
                        if (!APPEND_KEYS.contains(key)) values.remove(key);
                    } else if (APPEND_KEYS.contains(key)) {
                        List<Object> merged = new ArrayList<>();
                        Object previous = values.get(key);
                        if (previous instanceof List<?> list) merged.addAll(list);
                        append(merged, value);
                        values.put(key, copy(merged));
                    } else values.put(key, copy(value));
                });
    }

    public synchronized void replace(String key, Object value) {
        if (value == null) values.remove(key);
        else values.put(key, copy(value));
    }

    public synchronized RunState snapshot() {
        return new RunState(data());
    }

    private static void append(List<Object> target, Object value) {
        if (value instanceof Collection<?> collection) target.addAll(collection);
        else if (value.getClass().isArray())
            for (int i = 0; i < Array.getLength(value); i++) target.add(Array.get(value, i));
        else target.add(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(Map<String, Object> value) {
        return (Map<String, Object>) copy(value);
    }

    private static Object copy(Object value) {
        if (value instanceof GoalEntity goal) {
            // GoalEntity fields are immutable scalars, enums, and LocalDateTime values.
            GoalEntity result = new GoalEntity();
            BeanUtils.copyProperties(goal, result);
            return result;
        }
        if (value instanceof GraphEventPublisher.GraphEvent event)
            return new GraphEventPublisher.GraphEvent(
                    event.type(), metadata(event.data()), event.timestamp());
        if (value instanceof AssistantMessage message)
            return message.mutate().properties(metadata(message.getMetadata())).build();
        if (value instanceof UserMessage message)
            return message.mutate().metadata(metadata(message.getMetadata())).build();
        if (value instanceof SystemMessage message)
            return message.mutate().metadata(metadata(message.getMetadata())).build();
        if (value instanceof ToolResponseMessage message)
            return ToolResponseMessage.builder()
                    .responses(List.copyOf(message.getResponses()))
                    .metadata(metadata(message.getMetadata()))
                    .build();
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key, copy(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Set<?> set) {
            Set<Object> result = new LinkedHashSet<>();
            set.forEach(item -> result.add(copy(item)));
            return Collections.unmodifiableSet(result);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            collection.forEach(item -> result.add(copy(item)));
            return Collections.unmodifiableList(result);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object result = Array.newInstance(value.getClass().getComponentType(), length);
            for (int i = 0; i < length; i++) Array.set(result, i, copy(Array.get(value, i)));
            return result;
        }
        return value;
    }
}
