package vip.mate.tts;

import org.junit.jupiter.api.Test;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TtsVoiceSelectionTest {
    private final SystemSettingsDTO config = new SystemSettingsDTO();
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final TtsProvider edge = provider("edge-tts", "edge-voice", 100);
    private final TtsProvider cloud = provider("openai", "alloy", 200);
    private final ChatUploadLocationResolver uploads = mock(ChatUploadLocationResolver.class);
    private final TtsService service = new TtsService(settings,
            new TtsProviderRegistry(List.of(edge, cloud)), mock(ChatStreamTracker.class), uploads);

    TtsVoiceSelectionTest() {
        config.setTtsEnabled(true);
        config.setTtsProvider("edge-tts");
        config.setTtsFallbackEnabled(true);
        when(settings.getAllSettings()).thenReturn(config);
    }

    private TtsProvider provider(String id, String voice, int order) {
        var p = mock(TtsProvider.class);
        when(p.id()).thenReturn(id);
        when(p.autoDetectOrder()).thenReturn(order);
        when(p.availableVoices()).thenReturn(List.of(voice));
        when(p.defaultVoice()).thenReturn(voice);
        when(p.isAvailable(any())).thenReturn(true);
        return p;
    }

    @Test void previewUsesUnsavedSelectionWithoutWritingOrFallback() {
        config.setTtsDefaultVoice("old-voice");
        when(edge.synthesize(any(), any())).thenAnswer(inv -> {
            TtsRequest request = inv.getArgument(0);
            SystemSettingsDTO previewConfig = inv.getArgument(1);
            assertEquals("edge-voice", request.getVoice());
            assertEquals(1.4, request.getSpeed());
            assertEquals("", previewConfig.getTtsDefaultVoice());
            return TtsResult.failure("upstream failed");
        });
        assertFalse(service.preview("edge-tts", "edge-voice", 1.4, "Hello").isSuccess());
        assertEquals("old-voice", config.getTtsDefaultVoice());
        verify(cloud, never()).synthesize(any(), any());
        verifyNoInteractions(uploads);
    }

    @Test void previewRejectsWrongVoiceUnavailableProviderAndInvalidInput() {
        assertFalse(service.preview("edge-tts", "alloy", 1.0, "Hello").isSuccess());
        assertFalse(service.preview("unknown", "", 1.0, "Hello").isSuccess());
        assertFalse(service.preview("edge-tts", "", Double.NaN, "Hello").isSuccess());
        assertFalse(service.preview("edge-tts", "", 1.0, "x".repeat(201)).isSuccess());
        when(edge.isAvailable(any())).thenReturn(false);
        assertFalse(service.preview("edge-tts", "", 1.0, "Hello").isSuccess());
        verify(edge, never()).synthesize(any(), any());
    }

    @Test void previewDefaultDoesNotReuseSavedVoice() {
        config.setTtsDefaultVoice("edge-voice");
        when(edge.synthesize(any(), any())).thenAnswer(inv -> {
            assertEquals("", ((TtsRequest) inv.getArgument(0)).getVoice());
            assertEquals("", ((SystemSettingsDTO) inv.getArgument(1)).getTtsDefaultVoice());
            return TtsResult.success(new byte[]{1}, "audio/mpeg", "mp3");
        });
        assertTrue(service.preview("edge-tts", "", 1.0, "Hello").isSuccess());
        assertEquals("edge-voice", config.getTtsDefaultVoice());
    }

    @Test void fallbackDoesNotReceiveAnotherProvidersVoice() {
        when(edge.synthesize(any(), any())).thenReturn(TtsResult.failure("offline"));
        when(cloud.synthesize(any(), any())).thenAnswer(inv -> {
            TtsRequest request = inv.getArgument(0);
            assertNull(request.getVoice());
            assertNull(request.getModel());
            assertEquals("Hello", request.getText());
            assertEquals(1.2, request.getSpeed());
            return TtsResult.failure("test failure");
        });
        service.synthesize("test", "Hello", "edge-voice", 1.2, "mp3");
        verify(cloud).synthesize(any(), any());
    }
}
