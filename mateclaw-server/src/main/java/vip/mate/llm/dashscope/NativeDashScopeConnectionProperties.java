package vip.mate.llm.dashscope;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retains the existing native provider configuration keys. */
@ConfigurationProperties(prefix = "spring.ai.dashscope")
public class NativeDashScopeConnectionProperties {
    private Chat chat = new Chat();

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    private String apiKey;
    private String baseUrl = "https://dashscope.aliyuncs.com";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public static class Chat {
        private Options options = new Options();

        public Options getOptions() {
            return options;
        }

        public void setOptions(Options options) {
            this.options = options;
        }
    }

    public static class Options {
        private String model = "qwen-max";
        private Double temperature = 0.7;
        private Integer maxTokens = 4096;
        private Double topP;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double value) {
            temperature = value;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer value) {
            maxTokens = value;
        }

        public Double getTopP() {
            return topP;
        }

        public void setTopP(Double value) {
            topP = value;
        }
    }
}
