package vip.mate.tts;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * TTS 语音合成 REST 端点
 *
 * @author SnSclaw Team
 */
@RestController
@RequestMapping("/api/v1/tts")
@RequiredArgsConstructor
public class TtsController {

    private final TtsService ttsService;

    /**
     * 合成语音 — 前端"朗读"按钮调用
     */
    @PostMapping("/synthesize")
    public ResponseEntity<Map<String, Object>> synthesize(@RequestBody SynthesizeRequest req) {
        Map<String, Object> result = ttsService.synthesize(
                req.getConversationId(),
                req.getText(),
                req.getVoice(),
                req.getSpeed(),
                req.getFormat()
        );
        return ResponseEntity.ok(result);
    }

    /**
     * 列出所有可用语音
     */
    @GetMapping("/voices")
    public ResponseEntity<List<Map<String, Object>>> listVoices() {
        return ResponseEntity.ok(ttsService.listVoices());
    }

    @Data
    public static class SynthesizeRequest {
        private String conversationId;
        private String text;
        private String voice;
        private Double speed;
        private String format;
    }
}
