package com.huxizhen.travel.integration.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.integration.geo.DestinationResolver;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Open-Meteo 天气（无需 Key），失败时给出可执行的降级文案。
 *
 * <p>三个原实现里的坑都保留了：
 * <ul>
 *   <li>只提供未来 16 天预报，超出窗口要说清楚"还有几天"，而不是抛 HTTP 400；</li>
 *   <li>跨窗口的行程只截断到已知的最后一天，并说明；</li>
 *   <li>{@code indoor_priority = code>=51 或 降水>=60% 或 风速>=45}。</li>
 * </ul>
 */
@Service
public class WeatherService {

    public static final int FORECAST_WINDOW_DAYS = 16;
    private static final String USER_AGENT = "travel-agent-platform/1.0";
    private static final String GEOCODING_URL =
            "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=8&language=zh&format=json";
    private static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                    + "precipitation_probability_max,windspeed_10m_max&timezone=auto"
                    + "&start_date=%s&end_date=%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public Map<String, Object> getForecast(String destination, String startDate, String endDate) {
        LocalDate start = parseDay(startDate);
        LocalDate end = parseDay(endDate);
        if (end == null) {
            end = start;
        }
        if (start == null) {
            return unavailable(destination, "旅行日期格式无法识别，暂时无法查询天气。");
        }

        LocalDate today = LocalDate.now();
        LocalDate windowEnd = today.plusDays(FORECAST_WINDOW_DAYS);
        if (start.isAfter(windowEnd)) {
            long daysAway = java.time.temporal.ChronoUnit.DAYS.between(today, start);
            return unavailable(destination, "出行日期（" + startDate + "）距今天还有 " + daysAway
                    + " 天，超出天气服务能提供的未来 " + FORECAST_WINDOW_DAYS + " 天预报范围。"
                    + "请在出发前 " + FORECAST_WINDOW_DAYS + " 天内重新生成规划，"
                    + "届时这里会显示每天的天气和室内/户外建议。");
        }

        LocalDate clampedEnd = end.isBefore(windowEnd) ? end : windowEnd;
        boolean truncated = clampedEnd.isBefore(end);

        DestinationResolver.GeoPlace place = resolve(destination);
        if (place == null) {
            return unavailable(destination, "天气服务没有找到「" + destination + "」这个地点，暂时无法查询天气。"
                    + "把目的地改写成单个城市名（例如「东京」或「Tokyo」）后重新生成即可。");
        }

        JsonNode daily;
        try {
            String url = FORECAST_URL.formatted(
                    place.latitude(), place.longitude(), start, clampedEnd);
            JsonNode root = getJson(url);
            daily = root.path("daily");
        } catch (HttpStatusException error) {
            return unavailable(destination, "天气服务拒绝了这次查询（HTTP " + error.status + "）。"
                    + "出行日期可能超出预报范围，请稍后或临近出发日期重试。");
        } catch (java.io.IOException | RuntimeException error) {
            return unavailable(destination, "无法连接天气服务（网络异常），请检查网络后重试。");
        }

        List<Map<String, Object>> days = new ArrayList<>();
        JsonNode times = daily.path("time");
        for (int index = 0; index < times.size(); index++) {
            int code = daily.path("weather_code").path(index).asInt(0);
            int precipitation = daily.path("precipitation_probability_max").path(index).asInt(0);
            double wind = daily.path("windspeed_10m_max").path(index).asDouble(0);
            boolean indoor = code >= 51 || precipitation >= 60 || wind >= 45;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", times.path(index).asText());
            entry.put("weather_code", code);
            entry.put("precipitation_probability", precipitation);
            entry.put("indoor_priority", indoor);
            entry.put("condition", indoor ? "降雨或恶劣天气" : "适合户外活动");
            // Open-Meteo 偶尔缺温度：缺就不给键，避免页面渲染 "null–null℃"。
            JsonNode max = daily.path("temperature_2m_max").path(index);
            JsonNode min = daily.path("temperature_2m_min").path(index);
            if (!max.isMissingNode() && !max.isNull()) {
                entry.put("max_temperature", max.asDouble());
            }
            if (!min.isMissingNode() && !min.isNull()) {
                entry.put("min_temperature", min.asDouble());
            }
            days.add(entry);
        }
        if (days.isEmpty()) {
            return unavailable(destination, "天气服务暂时没有返回可用数据，请稍后重试。");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", destination);
        result.put("resolved_destination", place.label());
        result.put("resolved_country", place.country());
        result.put("days", days);
        result.put("status", "completed");
        result.put("source", "Open-Meteo");

        List<String> notes = new ArrayList<>();
        if (place.countryOnly()) {
            notes.add("「" + destination + "」是一个国家，这里按首都 " + place.label() + " 的天气查询。");
        } else if ("geocoder".equals(place.source()) && !place.label().isBlank()
                && !destination.contains(place.label())) {
            notes.add("天气按解析出的地点「" + place.label() + "」查询。");
        }
        if (truncated) {
            notes.add("出行日期超出未来 " + FORECAST_WINDOW_DAYS + " 天预报范围，这里只显示到 "
                    + clampedEnd + " 的天气；临近出发日期重新生成可获得完整预报。");
        }
        if (!notes.isEmpty()) {
            result.put("message", String.join("", notes));
        }
        return result;
    }

    private DestinationResolver.GeoPlace resolve(String destination) {
        DestinationResolver.GeoPlace place = DestinationResolver.resolveOffline(destination);
        String namedCountry = DestinationResolver.expectedCountry(destination);
        boolean wrongCountry = place != null
                && namedCountry != null
                && !DestinationResolver.normalizeCountry(place.country())
                        .equals(DestinationResolver.normalizeCountry(namedCountry));
        if (place != null && !wrongCountry) {
            return place;
        }
        for (String candidate : DestinationResolver.geocodeCandidates(destination)) {
            try {
                String url = GEOCODING_URL.formatted(URLEncoder.encode(candidate, StandardCharsets.UTF_8));
                JsonNode root = getJson(url);
                List<Map<String, Object>> results = new ArrayList<>();
                for (JsonNode item : root.path("results")) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String field : List.of("latitude", "longitude", "name", "country", "admin1", "feature_code")) {
                        if (item.hasNonNull(field)) {
                            row.put(field, item.get(field).isNumber()
                                    ? item.get(field).numberValue()
                                    : item.get(field).asText());
                        }
                    }
                    results.add(row);
                }
                DestinationResolver.GeoPlace chosen = DestinationResolver.chooseResult(results, namedCountry);
                if (chosen != null) {
                    return chosen;
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                // 试下一个写法
            }
        }
        return place;
    }

    private JsonNode getJson(String url) throws java.io.IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("interrupted", error);
        }
        if (response.statusCode() / 100 != 2) {
            throw new HttpStatusException(response.statusCode());
        }
        return MAPPER.readTree(response.body());
    }

    private static Map<String, Object> unavailable(String destination, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", destination);
        result.put("days", List.of());
        result.put("status", "unavailable");
        result.put("message", message);
        return result;
    }

    private static LocalDate parseDay(String value) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 非 2xx 的天气响应。 */
    static class HttpStatusException extends RuntimeException {
        final int status;

        HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }
}
