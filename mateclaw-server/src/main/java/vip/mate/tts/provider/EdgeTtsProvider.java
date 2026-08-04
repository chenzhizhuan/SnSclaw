package vip.mate.tts.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tts.TtsProvider;
import vip.mate.tts.TtsRequest;
import vip.mate.tts.TtsResult;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft Edge TTS Provider — 免费，无需 API Key
 * <p>
 * 使用 Edge 浏览器内置的 TTS WebSocket 协议。
 * 自动根据文本语言选择中文或英文语音。
 *
 * @author SnSclaw
 */
@Slf4j
@Component
public class EdgeTtsProvider implements TtsProvider {

    private static final String WS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String DEFAULT_VOICE_ZH = "zh-CN-XiaoxiaoNeural";
    private static final String DEFAULT_VOICE_EN = "en-US-MichelleNeural";
    private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    /**
     * Sec-MS-GEC 令牌的 Chromium 版本标识。微软按此校验客户端版本，
     * 过旧会被握手拒绝，需随上游 edge-tts 的 CHROMIUM_FULL_VERSION 同步更新。
     */
    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String SEC_MS_GEC_VERSION = "1-" + CHROMIUM_FULL_VERSION;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    /** Windows 文件时间纪元（1601-01-01）到 Unix 纪元的秒数差。 */
    private static final long WIN_EPOCH_OFFSET_SECONDS = 11644473600L;

    @Override
    public String id() {
        return "edge-tts";
    }

    @Override
    public String label() {
        return "Edge TTS (免费)";
    }

    @Override
    public boolean requiresCredential() {
        return false;
    }

    @Override
    public int autoDetectOrder() {
        return 100;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return true; // 始终可用，无需 Key
    }

    @Override
    public List<String> availableVoices() {
        return List.of(
                "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunjianNeural",
                "zh-CN-XiaoyiNeural", "zh-CN-YunyangNeural",
                "en-US-MichelleNeural", "en-US-GuyNeural", "en-US-JennyNeural",
                "en-US-AriaNeural", "en-US-DavisNeural",
                "ja-JP-NanamiNeural", "ko-KR-SunHiNeural"
        );
    }

    @Override
    public String defaultVoice() {
        return DEFAULT_VOICE_ZH;
    }

    @Override
    public TtsResult synthesize(TtsRequest request, SystemSettingsDTO config) {
        String voice = resolveVoice(request.getVoice(), request.getText(), config);
        String rate = speedToRate(request.getSpeed());

        try {
            byte[] audioData = synthesizeViaWebSocket(request.getText(), voice, rate);
            if (audioData == null || audioData.length == 0) {
                return TtsResult.failure("Edge TTS 未返回音频数据");
            }
            log.info("[Edge TTS] Synthesized {} bytes (voice={})", audioData.length, voice);
            return TtsResult.success(audioData, "audio/mpeg", "mp3");
        } catch (Exception e) {
            log.error("[Edge TTS] Synthesis error: {}", e.getMessage(), e);
            return TtsResult.failure("Edge TTS 合成失败: " + e.getMessage());
        }
    }

    private byte[] synthesizeViaWebSocket(String text, String voice, String rate) throws Exception {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String wsUrl = WS_URL + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&Sec-MS-GEC=" + generateSecMsGec()
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION
                + "&ConnectionId=" + requestId;

        ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        CompletableFuture<byte[]> resultFuture = new CompletableFuture<>();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        WebSocket ws = client.newWebSocketBuilder()
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", USER_AGENT)
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder textBuffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(Long.MAX_VALUE);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        textBuffer.append(data);
                        if (last) {
                            String msg = textBuffer.toString();
                            textBuffer.setLength(0);
                            if (msg.contains("turn.end")) {
                                resultFuture.complete(audioBuffer.toByteArray());
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        // 二进制帧：前 2 字节是 header 长度（大端），跳过 header
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        // 查找 "Path:audio\r\n" 后的音频数据
                        int headerEnd = findHeaderEnd(bytes);
                        if (headerEnd >= 0 && headerEnd < bytes.length) {
                            audioBuffer.write(bytes, headerEnd, bytes.length - headerEnd);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        if (!resultFuture.isDone()) {
                            resultFuture.complete(audioBuffer.toByteArray());
                        }
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        if (!resultFuture.isDone()) {
                            resultFuture.completeExceptionally(error);
                        }
                    }
                }).join();

        // 发送配置消息
        String configMsg = "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":false,\"wordBoundaryEnabled\":false},"
                + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}\r\n";
        ws.sendText(configMsg, true);

        // 发送 SSML
        String escapedText = escapeXml(text);
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + voice + "'>"
                + "<prosody pitch='+0Hz' rate='" + rate + "' volume='+0%'>"
                + escapedText
                + "</prosody></voice></speak>";

        String ssmlMsg = "X-RequestId:" + requestId + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "Path:ssml\r\n\r\n" + ssml;
        ws.sendText(ssmlMsg, true);

        // 等待结果（最多 60 秒）
        return resultFuture.get(60, TimeUnit.SECONDS);
    }

    /**
     * 生成 Sec-MS-GEC 令牌。
     * <p>
     * 微软 2024-10 起对该端点加了 DRM 校验，握手缺此参数一律返回 400。
     * 算法：当前 UTC 秒数移到 Windows 文件时间纪元、向下取整到 5 分钟边界、
     * 换算成 100 纳秒单位，拼上 TrustedClientToken 后取 SHA-256 大写十六进制。
     * <p>
     * 令牌按 5 分钟粒度变化，因此本机时钟偏差过大会导致握手失败。
     */
    private String generateSecMsGec() throws NoSuchAlgorithmException {
        long ticks = Instant.now().getEpochSecond() + WIN_EPOCH_OFFSET_SECONDS;
        ticks -= ticks % 300;
        // 转 100ns 单位。用 BigInteger 而不是 double：ticks * 10^7 超出 double 的
        // 53 位精确整数范围，直接乘会丢低位，算出的哈希与服务端不一致。
        BigInteger intervals = BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(10_000_000L));
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((intervals.toString() + TRUSTED_CLIENT_TOKEN).getBytes(StandardCharsets.US_ASCII));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    private int findHeaderEnd(byte[] data) {
        // 二进制帧格式：2 字节 header 长度（大端） + header + 音频数据
        if (data.length < 2) return -1;
        int headerLen = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        return 2 + headerLen;
    }

    private String resolveVoice(String requestedVoice, String text, SystemSettingsDTO config) {
        if (requestedVoice != null && !requestedVoice.isBlank()) {
            return requestedVoice;
        }
        String configVoice = config.getTtsDefaultVoice();
        if (configVoice != null && !configVoice.isBlank()) {
            return configVoice;
        }
        // 自动语言检测：CJK 字符比例 > 30% 使用中文语音
        return isCjkDominant(text) ? DEFAULT_VOICE_ZH : DEFAULT_VOICE_EN;
    }

    private boolean isCjkDominant(String text) {
        if (text == null || text.isEmpty()) return true;
        int cjkCount = 0;
        int totalAlphaNum = 0;
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                totalAlphaNum++;
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
                        || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA
                        || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA
                        || Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL) {
                    cjkCount++;
                }
            }
        }
        return totalAlphaNum == 0 || (double) cjkCount / totalAlphaNum > 0.3;
    }

    private String speedToRate(Double speed) {
        if (speed == null || speed == 1.0) return "+0%";
        int percent = (int) ((speed - 1.0) * 100);
        return (percent >= 0 ? "+" : "") + percent + "%";
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
