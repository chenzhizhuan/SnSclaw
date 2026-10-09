package vip.mate.llm.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaCaptionServiceAttachmentPathTest {
    @TempDir Path tempDir;

    @Test
    void webUploadUsesStoredNameInsteadOfClientPath() throws Exception {
        ChatUploadLocationResolver locations = mock(ChatUploadLocationResolver.class);
        ChatOrigin origin = ChatOrigin.web("conversation", "alice", 7L, null);
        Path image = Files.writeString(tempDir.resolve("saved.png"), "image");
        when(locations.resolveExistingFile(origin, "saved.png")).thenReturn(image);

        MessageContentPart part = new MessageContentPart();
        part.setStoredName("saved.png");
        part.setPath("chat-uploads/conversation/wrong.png");
        MediaCaptionService service = new MediaCaptionService(null, null);
        service.setUploadLocationResolver(locations);
        Method resolve = MediaCaptionService.class.getDeclaredMethod(
                "resolveMediaPath", MessageContentPart.class, ChatOrigin.class);
        resolve.setAccessible(true);

        assertEquals(image, resolve.invoke(service, part, origin));
    }
}
