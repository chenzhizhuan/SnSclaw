package vip.mate.tool.search;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Serper (Google Search) 搜索提供商 — 需要 API Key
 *
 * @author SnSclaw
 */
@Slf4j
@Component
public class SerperSearchProvider implements SearchProvider {

    private static final String DEFAULT_BASE_URL = "https://google.serper.dev/search";

    @Override
    public String id() {
        return "serper";
    }

    @Override
    public String label() {
        return "Serper (Google)";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 300;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        String key = config.getSerperApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public List<SearchResult> search(String query, SystemSettingsDTO config) {
        return search(SearchQuery.of(query), config);
    }

    @Override
    public List<SearchResult> search(SearchQuery searchQuery, SystemSettingsDTO config) {
        String apiKey = config.getSerperApiKey();
        String baseUrl = config.getSerperBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }

        JSONObject reqBody = new JSONObject()
                .set("q", searchQuery.query())
                .set("num", searchQuery.resolvedCount());

        // Serper freshness: tbs=qdr:d (day), qdr:w (week), qdr:m (month), qdr:y (year)
        if (searchQuery.hasFreshness()) {
            String tbs = mapFreshnessToTbs(searchQuery.freshness());
            if (tbs != null) reqBody.set("tbs", tbs);
        }
        // Serper language/region: gl (country), hl (interface language)
        if (searchQuery.hasLanguage()) {
            String lang = searchQuery.language().toLowerCase();
            if (lang.startsWith("zh")) {
                reqBody.set("gl", "cn").set("hl", "zh-cn");
            } else if (lang.startsWith("en")) {
                reqBody.set("gl", "us").set("hl", "en");
            } else if (lang.startsWith("ja")) {
                reqBody.set("gl", "jp").set("hl", "ja");
            }
        }

        String response = HttpUtil.createPost(baseUrl)
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .body(JSONUtil.toJsonStr(reqBody))
                .timeout(15000)
                .execute()
                .body();

        log.debug("Serper result for '{}': {}", searchQuery.query(), response);
        return parseResponse(response, searchQuery.resolvedCount());
    }

    private String mapFreshnessToTbs(String freshness) {
        return switch (freshness.toLowerCase()) {
            case "day" -> "qdr:d";
            case "week" -> "qdr:w";
            case "month" -> "qdr:m";
            case "year" -> "qdr:y";
            default -> null;
        };
    }

    private List<SearchResult> parseResponse(String response, int limit) {
        List<SearchResult> results = new ArrayList<>();
        try {
            JSONObject json = JSONUtil.parseObj(response);
            JSONArray organic = json.getJSONArray("organic");
            if (organic == null) return results;

            int max = Math.min(organic.size(), limit);
            for (int i = 0; i < max; i++) {
                JSONObject item = organic.getJSONObject(i);
                String url = item.getStr("link");
                results.add(SearchResult.builder()
                        .title(item.getStr("title"))
                        .url(url)
                        .snippet(item.getStr("snippet"))
                        .source(extractDomain(url))
                        .date(item.getStr("date"))
                        .providerId(id())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Serper 结果解析失败: {}", e.getMessage());
        }
        return results;
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
