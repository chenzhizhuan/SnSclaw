package vip.mate.tts.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tts.TtsProvider;
import vip.mate.tts.TtsRequest;
import vip.mate.tts.TtsResult;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft Edge TTS Provider 鈥?鍏嶈垂锛屾棤闇€ API Key
 * <p>
 * 浣跨敤 Edge 娴忚鍣ㄥ唴缃殑 TTS WebSocket 鍗忚銆?
 * 鑷姩鏍规嵁鏂囨湰璇█閫夋嫨涓枃鎴栬嫳鏂囪闊炽€?
 *
 * @author SnSclaw
 */
@Slf4j
@Component
public class EdgeTtsProvider implements TtsProvider {

    private static final String WS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String EDGE_VERSION = "143.0.3650.75";
    private static final String DEFAULT_VOICE_ZH = "zh-CN-XiaoxiaoNeural";
    private static final String DEFAULT_VOICE_EN = "en-US-MichelleNeural";
    private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    /**
     * Sec-MS-GEC 浠ょ墝鐨?Chromium 鐗堟湰鏍囪瘑銆傚井杞寜姝ゆ牎楠屽鎴风鐗堟湰锛?
     * 杩囨棫浼氳鎻℃墜鎷掔粷锛岄渶闅忎笂娓?edge-tts 鐨?CHROMIUM_FULL_VERSION 鍚屾鏇存柊銆?
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    /** Windows 鏂囦欢鏃堕棿绾厓锛?601-01-01锛夊埌 Unix 绾厓鐨勭鏁板樊銆?*/
    @Override
    public String id() {
        return "edge-tts";
    }

    @Override
    public String label() {
        return "Edge TTS (鍏嶈垂)";
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
        return true; // 濮嬬粓鍙敤锛屾棤闇€ Key
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
                return TtsResult.failure("Edge TTS 鏈繑鍥為煶棰戞暟鎹?);
            }
            log.info("[Edge TTS] Synthesized {} bytes (voice={})", audioData.length, voice);
            return TtsResult.success(audioData, "audio/mpeg", "mp3");
        } catch (Exception e) {
            String message = failureMessage(e);
            log.warn("[Edge TTS] {}", message);
            return TtsResult.failure(message);
        }
    }

    private byte[] synthesizeViaWebSocket(String text, String voice, String rate) throws Exception {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String wsUrl = WS_URL + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + requestId
                + "&Sec-MS-GEC=" + securityToken(Instant.now())
                + "&Sec-MS-GEC-Version=2-" + EDGE_VERSION;

        ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        CompletableFuture<byte[]> resultFuture = new CompletableFuture<>();

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {

            WebSocket ws = client.newWebSocketBuilder()
                    .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .connectTimeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT) + ";")
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        private final StringBuilder textBuffer = new StringBuilder();
                        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

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
                            byte[] fragment = new byte[data.remaining()];
                            data.get(fragment);
                            binaryBuffer.writeBytes(fragment);
                            if (last) {
                                byte[] bytes = binaryBuffer.toByteArray();
                                binaryBuffer.reset();
                                int headerEnd = findHeaderEnd(bytes);
                                if (headerEnd >= 2 && headerEnd <= bytes.length) {
                                    String headers = new String(bytes, 2, headerEnd - 2, StandardCharsets.UTF_8);
                                    if (headers.contains("Path:audio\r\n")) {
                                        audioBuffer.write(bytes, headerEnd, bytes.length - headerEnd);
                                    }
                                } else {
                                    resultFuture.completeExceptionally(new IllegalStateException("Invalid Edge TTS audio frame"));
                                }
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            if (!resultFuture.isDone()) {
                                resultFuture.completeExceptionally(new IllegalStateException(
                                        "Edge TTS connection closed before turn.end: " + statusCode));
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

            try {
                String timestamp = DateTimeFormatter.ofPattern(
                        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                        .withZone(ZoneOffset.UTC).format(Instant.now());
                // 鍙戦€侀厤缃秷鎭?
                String configMsg = "X-Timestamp:" + timestamp + "\r\nContent-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n"
                        + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                        + "\"sentenceBoundaryEnabled\":false,\"wordBoundaryEnabled\":false},"
                        + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}\r\n";
                ws.sendText(configMsg, true).join();

                // 鍙戦€?SSML
                String escapedText = escapeXml(text);
                String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                        + "<voice name='" + voice + "'>"
                        + "<prosody pitch='+0Hz' rate='" + rate + "' volume='+0%'>"
                        + escapedText
                        + "</prosody></voice></speak>";

                String ssmlMsg = "X-RequestId:" + requestId + "\r\n"
                        + "X-Timestamp:" + timestamp + "Z\r\n"
                        + "Content-Type:application/ssml+xml\r\n"
                        + "Path:ssml\r\n\r\n" + ssml;
                ws.sendText(ssmlMsg, true).join();

                // 绛夊緟缁撴灉锛堟渶澶?60 绉掞級
                return resultFuture.get(60, TimeUnit.SECONDS);
            } finally {
                ws.abort();
            }
        }
    }

    static String securityToken(Instant now) {
        return EdgeTtsSecurityToken.generate(now, TRUSTED_CLIENT_TOKEN);
    }

    static String failureMessage(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof WebSocketHandshakeException handshake) {
            int status = handshake.getResponse().statusCode();
            return "Edge TTS 杩炴帴鎻℃墜澶辫触 (HTTP " + status + ")銆傝妫€鏌ョ郴缁熸椂闂淬€佷唬鐞嗘垨缃戠粶璁剧疆锛涙湇鍔″崗璁篃鍙兘宸插彉鏇淬€?;
        }
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return "Edge TTS 鍚堟垚瓒呮椂锛岃妫€鏌ョ綉缁滄垨浠ｇ悊璁剧疆鍚庨噸璇曘€?;
        }
        return "Edge TTS 鍚堟垚澶辫触 (" + cause.getClass().getSimpleName()
                + ")锛岃妫€鏌ョ綉缁滄垨浠ｇ悊璁剧疆鍚庨噸璇曘€?;
    }

    private int findHeaderEnd(byte[] data) {
        // 浜岃繘鍒跺抚鏍煎紡锛? 瀛楄妭 header 闀垮害锛堝ぇ绔級 + header + 闊抽鏁版嵁
        if (data.length < 2) return -1;
        int headerLen = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        return 2 + headerLen;
    }

    private String resolveVoice(String requestedVoice, String text, SystemSettingsDTO config) {
        if (requestedVoice != null && !requestedVoice.isBlank()) {
            return requestedVoice;
        }
        String configVoice = config.getTtsDefaultVoice();
        if (configVoice != null && availableVoices().contains(configVoice)) {
            return configVoice;
        }
        // 鑷姩璇█妫€娴嬶細CJK 瀛楃姣斾緥 > 30% 浣跨敤涓枃璇煶
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
