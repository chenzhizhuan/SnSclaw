package vip.mate.tts.provider;

import org.junit.jupiter.api.Test;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tts.TtsRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EdgeTtsProviderTest {
    @Test
    void hashesWindowsFileTimeInFiveMinuteBuckets() throws Exception {
        assertEquals("492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
                EdgeTtsProvider.securityToken(java.time.Instant.ofEpochSecond(1791888000)));
        assertEquals(EdgeTtsProvider.securityToken(java.time.Instant.EPOCH),
                EdgeTtsProvider.securityToken(java.time.Instant.ofEpochSecond(299)));
        assertNotEquals(EdgeTtsProvider.securityToken(java.time.Instant.EPOCH),
                EdgeTtsProvider.securityToken(java.time.Instant.ofEpochSecond(300)));
    }

    @Test
    void matchesUpstreamV2ReferenceVectors() {
        // Values produced by upstream edge_tts.drm.generate_sec_ms_gec_v2.
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
                EdgeTtsProvider.securityToken(java.time.Instant.EPOCH));
        assertEquals("F7535A20A262E331-28675BF85C7106BBF20035A78FCB0B6890813EAD5C3B983EF3424931B720BABB",
                EdgeTtsProvider.securityToken(java.time.Instant.ofEpochSecond(300)));
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
                EdgeTtsProvider.securityToken(java.time.Instant.ofEpochSecond(1700000000)));
    }

    @Test
    void reportsHandshakeStatusWithoutExposingResponseUri() {
        java.net.http.HttpResponse<?> response = mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(403);
        var failure = new java.util.concurrent.CompletionException(
                new java.net.http.WebSocketHandshakeException(response));
        String message = EdgeTtsProvider.failureMessage(failure);
        assertTrue(message.contains("HTTP 403"));
        assertTrue(message.contains("系统时间"));
        assertFalse(message.contains("TrustedClientToken"));
        assertFalse(message.contains("Sec-MS-GEC"));
        verify(response, never()).uri();
    }

    @Test
    void usesCurrentHandshakeAndPreservesFragmentedAudio() {
        HttpClient.Builder httpBuilder = mock(HttpClient.Builder.class, RETURNS_SELF);
        HttpClient client = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class, RETURNS_SELF);
        WebSocket socket = mock(WebSocket.class);
        when(httpBuilder.build()).thenReturn(client);
        when(client.newWebSocketBuilder()).thenReturn(wsBuilder);
        AtomicReference<URI> endpoint = new AtomicReference<>();
        AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(inv -> {
            endpoint.set(inv.getArgument(0));
            listener.set(inv.getArgument(1));
            return CompletableFuture.completedFuture(socket);
        });
        when(socket.sendText(anyString(), eq(true))).thenAnswer(inv -> {
            if (inv.<String>getArgument(0).contains("Path:ssml")) {
                byte[] headers = "Path:audio\r\nContent-Type:audio/mpeg\r\n".getBytes(StandardCharsets.UTF_8);
                byte[] packet = ByteBuffer.allocate(2 + headers.length + 4)
                        .putShort((short) headers.length).put(headers).put(new byte[]{1, 2, 3, 4}).array();
                listener.get().onBinary(socket, ByteBuffer.wrap(Arrays.copyOfRange(packet, 0, 1)), false);
                listener.get().onBinary(socket, ByteBuffer.wrap(Arrays.copyOfRange(packet, 1, packet.length)), true);
                listener.get().onText(socket, "Path:turn.end\r\n", true);
            }
            return CompletableFuture.completedFuture(socket);
        });
        try (var clients = mockStatic(HttpClient.class)) {
            clients.when(HttpClient::newBuilder).thenReturn(httpBuilder);
            var result = new EdgeTtsProvider().synthesize(
                    TtsRequest.builder().text("Hello").build(), new SystemSettingsDTO());
            assertEquals("/consumer/speech/synthesize/readaloud/edge/v1", endpoint.get().getPath());
            assertTrue(endpoint.get().getQuery().matches(".*Sec-MS-GEC=[A-F0-9]{16}-[A-F0-9]{64}.*"));
            assertTrue(endpoint.get().getQuery().contains("Sec-MS-GEC-Version=2-"));
            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, result.getAudioData());
            verify(socket).abort();
            verify(client).close();
        }
    }
}
