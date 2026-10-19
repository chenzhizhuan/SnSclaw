package vip.mate.llm.chatmodel;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

/** Reads defaults from current models and plugins implementing the legacy model contract. */
public final class ChatModelDefaults {
    private ChatModelDefaults() {}

    @SuppressWarnings("removal")
    public static ChatOptions optionsFor(ChatModel model) {
        ChatOptions legacyDefaults = model.getDefaultOptions();
        return legacyDefaults != null ? legacyDefaults : model.getOptions();
    }
}
