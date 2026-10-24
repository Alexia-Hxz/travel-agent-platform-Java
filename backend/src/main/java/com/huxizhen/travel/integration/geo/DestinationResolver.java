package com.huxizhen.travel.integration.geo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把用户写的目的地解析成一个可查询的地点。
 *
 * <p>先查离线表，再退回地理编码器。离线表是"地理编码器真实返回过的答案"，因为中文城市名
 * 在编码器那边并不可靠：实测「东京」会解析到江苏的一个村，「伦敦」会解析到安大略，
 * 「罗马」会解析到昆士兰。
 */
public final class DestinationResolver {

    private static final String[] SEPARATORS = {" ", ",", "，", "、", "/", "\\", "|", "·", "・", "—", "–", "~", "-"};
    private static final String[] SUFFIXES = {"特别市", "自治市", "都", "市", "府", "县", "区", "省", "州", "町", "村"};

    private DestinationResolver() {
    }

    /** 解析结果。 */
    public record GeoPlace(
            double latitude,
            double longitude,
            String label,
            String country,
            boolean countryOnly,
            String source) {
    }

    public static String fold(String value) {
        if (value == null) {
            return "";
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("　", "");
        for (String suffix : SUFFIXES) {
            if (text.endsWith(suffix) && text.length() > suffix.length()) {
                text = text.substring(0, text.length() - suffix.length());
            }
        }
        return text;
    }

    private static final Map<String, String> KEY_INDEX;
    private static final Map<String, String> ALIAS_INDEX;
    private static final Set<String> COUNTRY_NAMES;

    static {
        Map<String, String> keys = new java.util.HashMap<>();
        DestinationData.CITY_ROWS.forEach((key, row) -> keys.put(fold(key), key));
        KEY_INDEX = Map.copyOf(keys);

        Map<String, String> aliases = new java.util.HashMap<>();
        DestinationData.DESTINATION_ALIASES.forEach((name, key) -> aliases.put(fold(name), key));
        ALIAS_INDEX = Map.copyOf(aliases);

        Set<String> countries = new java.util.LinkedHashSet<>();
        DestinationData.CITY_ROWS.values().forEach(row -> countries.add(row.country()));
        COUNTRY_NAMES = Set.copyOf(countries);
    }

    /** 按分隔符与后缀拆出的候选 key，按可信度排序。 */
    static List<String> keysOf(String text) {
        List<String> candidates = new ArrayList<>();
        String folded = fold(text);
        if (!folded.isEmpty()) {
            candidates.add(folded);
        }
        for (String separator : SEPARATORS) {
            if (text == null || !text.contains(separator)) {
                continue;
            }
            for (String part : text.split(java.util.regex.Pattern.quote(separator))) {
                String key = fold(part);
                if (!key.isEmpty() && !candidates.contains(key)) {
                    candidates.add(key);
                }
            }
        }
        return candidates;
    }

    public static String normalizeCountry(String value) {
        return value == null ? "" : fold(value);
    }

    /** 离线表命中返回地点，否则 null。 */
    public static GeoPlace resolveOffline(String destination) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        for (String candidate : keysOf(destination)) {
            String key = ALIAS_INDEX.get(candidate);
            if (key == null) {
                key = KEY_INDEX.get(candidate);
            }
            if (key != null) {
                DestinationData.CityRow row = DestinationData.CITY_ROWS.get(key);
                return new GeoPlace(row.latitude(), row.longitude(), row.label(), row.country(), false, "table");
            }
        }
        // 前缀带国家名时去掉再试一次（"日本东京" / "中国上海"）
        for (Map.Entry<String, String[]> entry : DestinationData.COUNTRY_PREFIXES.entrySet()) {
            if (destination.startsWith(entry.getKey())) {
                String stripped = destination.substring(entry.getKey().length()).trim();
                if (!stripped.isEmpty()) {
                    GeoPlace inner = resolveOffline(stripped);
                    if (inner != null) {
                        return inner;
                    }
                }
            }
        }
        // 只给了国家名：用该国的第一条城市行代表（原实现标记 country_only）
        String folded = fold(destination);
        for (Map.Entry<String, DestinationData.CityRow> entry : DestinationData.CITY_ROWS.entrySet()) {
            if (fold(entry.getValue().country()).equals(folded)) {
                DestinationData.CityRow row = entry.getValue();
                return new GeoPlace(row.latitude(), row.longitude(), row.label(), row.country(), true, "table");
            }
        }
        return null;
    }

    /** 用户明确点名的国家（写进目的地字符串里的那个）。 */
    public static String expectedCountry(String destination) {
        if (destination == null) {
            return null;
        }
        for (String country : COUNTRY_NAMES) {
            if (!country.isBlank() && destination.contains(country)) {
                return country;
            }
        }
        return null;
    }

    /** 交给地理编码器的写法：先净名，再英文 key，最后原文。 */
    public static List<String> geocodeCandidates(String destination) {
        Set<String> candidates = new LinkedHashSet<>();
        if (destination != null) {
            String stripped = destination.strip();
            for (Map.Entry<String, String[]> entry : DestinationData.COUNTRY_PREFIXES.entrySet()) {
                if (stripped.startsWith(entry.getKey())) {
                    stripped = stripped.substring(entry.getKey().length()).strip();
                    break;
                }
            }
            if (!stripped.isEmpty()) {
                candidates.add(stripped);
            }
            String key = ALIAS_INDEX.get(fold(stripped));
            if (key != null) {
                candidates.add(key);
            }
            candidates.add(destination.strip());
        }
        return new ArrayList<>(candidates);
    }

    /**
     * 从地理编码器的候选里挑一个：国家必须对得上，再按城市的行政级别排序。
     */
    public static GeoPlace chooseResult(List<Map<String, Object>> results, String expectedCountry) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        String wanted = normalizeCountry(expectedCountry);
        List<Map<String, Object>> ordered = new ArrayList<>(results);
        ordered.sort((left, right) -> Integer.compare(rank(right, wanted), rank(left, wanted)));

        for (Map<String, Object> result : ordered) {
            String country = String.valueOf(result.getOrDefault("country", ""));
            if (!wanted.isEmpty() && !normalizeCountry(country).contains(wanted)) {
                continue;
            }
            GeoPlace place = toPlace(result);
            if (place != null) {
                return place;
            }
        }
        for (Map<String, Object> result : ordered) {
            GeoPlace place = toPlace(result);
            if (place != null) {
                return place;
            }
        }
        return null;
    }

    private static int rank(Map<String, Object> result, String wanted) {
        int score = 0;
        String country = normalizeCountry(String.valueOf(result.getOrDefault("country", "")));
        if (!wanted.isEmpty() && country.contains(wanted)) {
            score += 100;
        }
        String feature = String.valueOf(result.getOrDefault("feature_code", ""));
        score += switch (feature) {
            case "PPLC" -> 5;
            case "PPLA" -> 4;
            case "PPLA2" -> 3;
            case "PPLA3" -> 2;
            default -> 1;
        };
        return score;
    }

    @SuppressWarnings("unchecked")
    private static GeoPlace toPlace(Map<String, Object> result) {
        Object latitude = result.get("latitude");
        Object longitude = result.get("longitude");
        if (!(latitude instanceof Number) || !(longitude instanceof Number)) {
            return null;
        }
        String name = String.valueOf(result.getOrDefault("name", ""));
        String country = String.valueOf(result.getOrDefault("country", ""));
        String label = String.valueOf(result.getOrDefault("admin1", name));
        return new GeoPlace(
                ((Number) latitude).doubleValue(),
                ((Number) longitude).doubleValue(),
                label.isBlank() || "null".equals(label) ? name : label + (label.contains(name) ? "" : "（" + name + "）"),
                country,
                false,
                "geocoder");
    }
}
