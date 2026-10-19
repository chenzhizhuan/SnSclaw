package vip.mate.llm;

import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIServiceException;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

import vip.mate.llm.dashscope.NativeDashScopeException;

/** HTTP details shared by model retry and error classification. */
public final class ProviderHttpError {
    private ProviderHttpError() {}

    public static int status(Throwable error) {
        if (error instanceof OpenAIServiceException sdk) return sdk.statusCode();
        if (error instanceof AnthropicServiceException sdk) return sdk.statusCode();
        if (error instanceof NativeDashScopeException nativeError) return nativeError.getStatus();
        if (error instanceof RestClientResponseException response)
            return response.getStatusCode().value();
        return 0;
    }

    public static HttpHeaders headers(Throwable error) {
        if (error instanceof OpenAIServiceException sdk) {
            HttpHeaders headers = new HttpHeaders();
            sdk.headers().names().forEach(name -> headers.put(name, sdk.headers().values(name)));
            return headers;
        }
        if (error instanceof AnthropicServiceException sdk) {
            HttpHeaders headers = new HttpHeaders();
            sdk.headers().names().forEach(name -> headers.put(name, sdk.headers().values(name)));
            return headers;
        }
        if (error instanceof RestClientResponseException response)
            return response.getResponseHeaders();
        return null;
    }

    public static String body(Throwable error) {
        if (error instanceof OpenAIServiceException sdk) return sdk.body().toString();
        if (error instanceof AnthropicServiceException sdk) return sdk.body().toString();
        if (error instanceof RestClientResponseException response)
            return response.getResponseBodyAsString();
        return "";
    }
}
