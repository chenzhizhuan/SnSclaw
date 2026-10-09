package vip.mate.tts.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tts.TtsRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiTtsVoiceTest {
    @Test void usesSavedVoiceAndAllowsExplicitOverride() throws Exception {
        var models = mock(ModelProviderService.class, RETURNS_DEEP_STUBS);
        when(models.getProviderConfig("openai").getApiKey()).thenReturn("test-key");
        when(models.getProviderConfig("openai").getBaseUrl()).thenReturn("https://example.invalid");
        var mapper = new ObjectMapper();
        var provider = new OpenAiTtsProvider(models, mapper);
        var config = new SystemSettingsDTO();
        var http = mock(HttpRequest.class, RETURNS_SELF);
        doReturn(http).when(http).header(anyString(), anyString());
        var response = mock(HttpResponse.class);
        when(http.execute()).thenReturn(response);
        when(response.getStatus()).thenReturn(200);
        when(response.bodyBytes()).thenReturn(new byte[]{1});
        try (var factory = mockStatic(HttpRequest.class)) {
            factory.when(() -> HttpRequest.post(anyString())).thenReturn(http);
            config.setTtsDefaultVoice("nova");
            assertTrue(provider.synthesize(TtsRequest.builder().text("Hi").build(), config).isSuccess());
            verify(http).body(contains("\"voice\":\"nova\""));
            provider.synthesize(TtsRequest.builder().text("Hi").voice("echo").build(), config);
            verify(http).body(contains("\"voice\":\"echo\""));
            config.setTtsDefaultVoice("zh-CN-XiaoxiaoNeural");
            provider.synthesize(TtsRequest.builder().text("Hi").build(), config);
            verify(http).body(contains("\"voice\":\"alloy\""));
        }
    }
}
