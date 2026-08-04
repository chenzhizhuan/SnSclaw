package vip.mate.tts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TTS 语音合成服务 — 核心编排，处理 provider 选择、文本预处理、fallback、文件保存
 *
 * @author SnSclaw
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {

    private final SystemSettingService systemSettingService;
    private final TtsProviderRegistry providerRegistry;
    private final ChatStreamTracker streamTracker;
    private final ChatUploadLocationResolver uploadLocationResolver;

    private static final int MAX_TEXT_LENGTH = 4096;

    /**
     * 自动朗读的合成超时。因为调用点在 {@code done} 之后，这段等待不影响前端
     * 的"生成完成"状态，只延后 SSE 连接关闭；超时则放弃朗读，不阻塞会话。
     */
    private static final int AUTO_SYNTHESIZE_TIMEOUT_SECONDS = 20;

    /**
     * 自动 TTS 线程池。
     * <p>
     * 用有界队列 + AbortPolicy，不能用 {@code newFixedThreadPool} 的无界队列：
     * 单次合成最长可占用一个工作线程 60 秒（provider 自身超时，还可能叠加
     * fallback），而等待方只给 20 秒。队列无界时，超出吞吐的请求会无限积压，
     * 每一个都在请求方早已放弃后才开始合成 —— 超时从此自我维持。
     * 有界队列让过载时立刻拒绝（catch 到 RejectedExecutionException 记日志），
     * 这是尽力而为的功能，丢弃比堆积更合适。
     */
    private final ExecutorService ttsExecutor = new ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(8),
            r -> {
                Thread t = new Thread(r, "tts-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /**
     * 合成语音并保存为文件
     *
     * @return { success, audioUrl, contentType, providerName }
     */
    public Map<String, Object> synthesize(String conversationId, String text,
                                            String voice, Double speed, String format) {
        SystemSettingsDTO config = systemSettingService.getAllSettings();

        if (!Boolean.TRUE.equals(config.getTtsEnabled())) {
            return Map.of("success", false, "error", "TTS 功能未启用，请在系统设置中开启");
        }

        // 文本预处理
        String cleanText = preprocessText(text);
        if (cleanText.isBlank()) {
            return Map.of("success", false, "error", "待合成的文本为空");
        }

        // 构建请求
        TtsRequest request = TtsRequest.builder()
                .text(cleanText)
                .voice(voice)
                .speed(speed != null ? speed : config.getTtsSpeed())
                .format(format != null ? format : "mp3")
                .build();

        // Provider 选择 + fallback
        TtsResult result = synthesizeWithFallback(request, config);
        if (!result.isSuccess()) {
            return Map.of("success", false, "error", result.getErrorMessage());
        }

        // 保存文件
        try {
            String fileId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Path filePath = saveAudioFile(conversationId, fileId, result.getAudioData(), result.getFormat());
            String audioUrl = "/api/v1/chat/files/" + conversationId + "/" + filePath.getFileName();

            // audioUrl 是给浏览器用的 HTTP 路径；filePath 是落盘绝对路径。
            // 两者都返回：WebSocket 场景（TalkMode）要直接读字节推二进制帧，
            // 不能拿 audioUrl 当文件路径 —— 它以 "/" 开头，在 Windows 上会被
            // Paths.get() 判成 absolute，指向 C:\api\v1\... 这种不存在的位置。
            return Map.of(
                    "success", true,
                    "audioUrl", audioUrl,
                    "filePath", filePath.toAbsolutePath().toString(),
                    "contentType", result.getContentType(),
                    "format", result.getFormat()
            );
        } catch (IOException e) {
            log.error("[TTS] Failed to save audio file: {}", e.getMessage(), e);
            return Map.of("success", false, "error", "音频文件保存失败: " + e.getMessage());
        }
    }

    /**
     * 自动 TTS：消息完成后触发，通过 SSE 的 {@code tts_ready} 广播结果。
     * <p>
     * <b>调用时机与同步性是一对约束，改动前请读完这段。</b>
     * <p>
     * 必须在 {@code done} 广播<b>之后</b>调用，且<b>同步等待</b>合成完成：
     * <ul>
     *   <li>放在 {@code done} <b>之前</b>同步等待 → 推迟 {@code done}，DB 里的
     *       {@code streamStatus} 滞留 {@code running}，ChatConsole 的 4 秒轮询
     *       误判为"外部渠道正在跑"而 {@code reconnectStream}，把已落库的回复
     *       叠到本地占位气泡上 → 回复重复、几秒后收敛。</li>
     *   <li>改成<b>异步</b>（submit 后立即返回）→ 调用方的 {@code finally} 紧接着
     *       {@code completeEmitterQuietly(emitter)} 关闭 SSE；合成要数秒，广播时
     *       已无活跃订阅者，前端读取循环也早已退出，事件进了 ring buffer 却无人
     *       取用（此时不会再有重连）→ 后端日志有 Auto-synthesized，前端不出声。</li>
     * </ul>
     * 放在 {@code done} 之后同步等待，两个坑都避开：前端已收到完成状态（UI 不卡），
     * 而 HTTP 连接尚未关闭（由 {@code finally} 负责），事件能直达活跃订阅者。
     * <p>
     * 超时后放弃朗读，不阻塞会话。
     */
    public void autoSynthesize(String conversationId, String text) {
        // abandoned 由等待方在超时时置位，任务体据此放弃广播。
        // 只 cancel(true) 不够：合成可能已过了中断检查点，任务仍会跑到广播那步，
        // 而 tts_ready 会写入 ring buffer 存活 DONE_RETENTION_MS，导致几分钟后
        // 的一次重连把这段"已放弃"的语音突然播出来。
        AtomicBoolean abandoned = new AtomicBoolean(false);
        Future<?> task = null;
        try {
            task = ttsExecutor.submit(() -> {
                try {
                    if (abandoned.get()) {
                        log.debug("[TTS] Auto-synthesize abandoned before start for {}", conversationId);
                        return;
                    }
                    Map<String, Object> result = synthesize(conversationId, text, null, null, null);
                    if (!Boolean.TRUE.equals(result.get("success"))) {
                        log.warn("[TTS] Auto-synthesize failed for {}: {}", conversationId, result.get("error"));
                        return;
                    }
                    if (abandoned.get()) {
                        log.warn("[TTS] Auto-synthesize completed after timeout for {} — dropping playback",
                                conversationId);
                        return;
                    }
                    Map<String, Object> event = new HashMap<>();
                    event.put("audioUrl", result.get("audioUrl"));
                    event.put("contentType", result.get("contentType"));
                    streamTracker.broadcastObject(conversationId, "tts_ready", event);
                    log.info("[TTS] Auto-synthesized for conversation {}", conversationId);
                } catch (Exception e) {
                    // 任务体内部兜底：超时后等待方不再调用 get()，
                    // 异常若只靠 ExecutionException 冒泡就会彻底消失，无任何日志。
                    log.error("[TTS] Auto-synthesize task error for {}: {}", conversationId, e.getMessage(), e);
                }
            });
            task.get(AUTO_SYNTHESIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            abandoned.set(true);
            task.cancel(true);
            log.warn("[TTS] Auto-synthesize timed out after {}s for {} — skipping playback",
                    AUTO_SYNTHESIZE_TIMEOUT_SECONDS, conversationId);
        } catch (InterruptedException e) {
            // 恢复中断标志：调用链跑在 SSE 的 doOnComplete 上，与 dispose() 取消
            // 路径共处，吞掉标志会让上层的取消失效。
            abandoned.set(true);
            if (task != null) task.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[TTS] Auto-synthesize interrupted for {}", conversationId);
        } catch (RejectedExecutionException e) {
            // 队列已满：合成慢于消息产出速度。丢弃这次朗读，不排队等。
            log.warn("[TTS] Auto-synthesize rejected (queue full) for {} — skipping playback", conversationId);
        } catch (Exception e) {
            log.error("[TTS] Auto-synthesize failed for {}: {}", conversationId, e.getMessage(), e);
        }
    }

    /**
     * 检查 TTS 功能是否全局启用（供 ChannelMessageRouter 等外部组件调用）
     */
    public boolean isTtsEnabled() {
        SystemSettingsDTO config = systemSettingService.getSettings();
        return Boolean.TRUE.equals(config.getTtsEnabled());
    }

    /**
     * 获取当前配置是否开启自动 TTS
     */
    public boolean isAutoModeEnabled() {
        SystemSettingsDTO config = systemSettingService.getSettings();
        return Boolean.TRUE.equals(config.getTtsEnabled())
                && "always".equals(config.getTtsAutoMode());
    }

    /**
     * 列出所有可用的语音
     */
    public List<Map<String, Object>> listVoices() {
        SystemSettingsDTO config = systemSettingService.getAllSettings();
        List<Map<String, Object>> voices = new ArrayList<>();

        for (TtsProvider provider : providerRegistry.allSorted()) {
            boolean available = provider.isAvailable(config);
            for (String voice : provider.availableVoices()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("voice", voice);
                info.put("provider", provider.id());
                info.put("providerLabel", provider.label());
                info.put("available", available);
                info.put("isDefault", voice.equals(provider.defaultVoice()));
                voices.add(info);
            }
        }
        return voices;
    }

    // ==================== 内部逻辑 ====================

    private TtsResult synthesizeWithFallback(TtsRequest request, SystemSettingsDTO config) {
        TtsProvider primary = providerRegistry.resolve(config);
        if (primary == null) {
            return TtsResult.failure("没有可用的 TTS Provider，请检查配置");
        }

        TtsResult result = primary.synthesize(request, config);
        if (result.isSuccess()) {
            return result;
        }

        // Fallback
        List<String> errors = new ArrayList<>();
        errors.add(primary.id() + ": " + result.getErrorMessage());

        if (Boolean.TRUE.equals(config.getTtsFallbackEnabled())) {
            for (TtsProvider fb : providerRegistry.fallbackCandidates(config, primary.id())) {
                log.info("[TTS] Trying fallback provider: {}", fb.id());
                result = fb.synthesize(request, config);
                if (result.isSuccess()) {
                    return result;
                }
                errors.add(fb.id() + ": " + result.getErrorMessage());
            }
        }

        return TtsResult.failure("所有 TTS Provider 均失败\n" + String.join("\n", errors));
    }

    private String preprocessText(String text) {
        if (text == null) return "";
        // 去除 Markdown 格式
        String clean = text
                .replaceAll("```[\\s\\S]*?```", "") // 代码块
                .replaceAll("`[^`]+`", "")           // 行内代码
                .replaceAll("!?\\[([^\\]]*)\\]\\([^)]+\\)", "$1") // 链接/图片
                .replaceAll("[*_~]{1,3}", "")         // 加粗/斜体/删除线
                .replaceAll("^#{1,6}\\s+", "")        // 标题
                .replaceAll("^[\\-*+]\\s+", "")       // 列表
                .replaceAll("^>\\s+", "")             // 引用
                .replaceAll("\\|[^|]+\\|", "")        // 表格
                .replaceAll("\n{3,}", "\n\n")          // 多余空行
                .trim();

        // 截断
        if (clean.length() > MAX_TEXT_LENGTH) {
            clean = clean.substring(0, MAX_TEXT_LENGTH);
        }
        return clean;
    }

    private Path saveAudioFile(String conversationId, String fileId, byte[] data, String format)
            throws IOException {
        Path dir = uploadLocationResolver.resolveWriteDir(conversationId);
        Files.createDirectories(dir);
        String fileName = "tts_" + fileId + "." + format;
        Path filePath = dir.resolve(fileName);
        Files.write(filePath, data);
        return filePath;
    }
}
