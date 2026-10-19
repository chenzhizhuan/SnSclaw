package vip.mate.agent.graph.node;

import vip.mate.agent.runtime.state.RunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.config.GraphObservationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * ObservationNode 重复观察检测单元测试
 */
class ObservationNodeDuplicateTest {

    private ObservationNode createNode() {
        return new ObservationNode(new ObservationProcessor(new GraphObservationProperties()));
    }

    /**
     * 构建 RunState，包含指定的观察历史和工具结果
     */
    private RunState buildState(List<String> observationHistory, int iteration, int maxIter) {
        Map<String, Object> stateMap = new HashMap<>();
        stateMap.put(CURRENT_ITERATION, iteration);
        stateMap.put(MAX_ITERATIONS, maxIter);
        stateMap.put(OBSERVATION_HISTORY, new ArrayList<>(observationHistory));
        stateMap.put(TOOL_RESULTS, List.of());
        stateMap.put(TOOL_CALL_COUNT, 0);
        return new RunState(stateMap);
    }

    @Test
    @DisplayName("空观察不应触发重复检测（空结果是合法的边界情况）")
    void shouldNotTriggerForEmptyObservations() throws Exception {
        ObservationNode node = createNode();
        // TOOL_RESULTS 为空时 combinedObservation = ""，
        // detectDuplicateObservations 对空字符串返回 false（by design）
        List<String> history = List.of("", "");
        RunState state = buildState(history, 2, 10);

        Map<String, Object> result = node.apply(state);
        assertNull(result.get(ERROR), "Empty observations should not trigger duplicate detection");
    }

    @Test
    @DisplayName("观察历史少于阈值时不触发重复检测")
    void shouldNotTriggerWhenHistoryTooShort() throws Exception {
        ObservationNode node = createNode();
        // 只有 1 条历史，threshold=3 需要至少 2 条匹配
        List<String> history = List.of("");
        RunState state = buildState(history, 1, 10);

        Map<String, Object> result = node.apply(state);
        assertNull(result.get(ERROR));
    }

    @Test
    @DisplayName("不同的历史观察不触发重复检测")
    void shouldNotTriggerWhenHistoryDiffers() throws Exception {
        ObservationNode node = createNode();
        List<String> history = List.of("result A", "result B");
        RunState state = buildState(history, 2, 10);

        Map<String, Object> result = node.apply(state);
        assertNull(result.get(ERROR));
    }

    @Test
    @DisplayName("空历史不触发重复检测")
    void shouldNotTriggerWithEmptyHistory() throws Exception {
        ObservationNode node = createNode();
        RunState state = buildState(List.of(), 0, 10);

        Map<String, Object> result = node.apply(state);
        assertNull(result.get(ERROR));
    }
}
