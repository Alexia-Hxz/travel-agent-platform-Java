package com.huxizhen.travel.agent.tool;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时间冲突检测，逐行对齐 Python 的 {@code conflicts.py}。
 *
 * <p>同一天内按开始时间排序后两层扫描；因为已排序，一旦 {@code second.start >= first.end}
 * 就可以 break——后面的项只会更晚。
 */
public final class ConflictDetector {

    private ConflictDetector() {
    }

    public record Conflict(
            LocalDate date, String firstPlace, String secondPlace, String message) {
    }

    public static List<Conflict> detect(List<Map<String, Object>> items) {
        List<Conflict> conflicts = new ArrayList<>();
        Map<LocalDate, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            LocalDate date = asDate(item.get("date"));
            if (date == null) {
                continue;
            }
            grouped.computeIfAbsent(date, key -> new ArrayList<>()).add(item);
        }

        for (Map.Entry<LocalDate, List<Map<String, Object>>> entry : grouped.entrySet()) {
            List<Map<String, Object>> dayItems = new ArrayList<>(entry.getValue());
            dayItems.sort(Comparator
                    .comparing((Map<String, Object> item) -> asTime(item.get("startTime"), item.get("start_time")))
                    .thenComparing(item -> asTime(item.get("endTime"), item.get("end_time"))));

            for (int index = 0; index < dayItems.size(); index++) {
                Map<String, Object> first = dayItems.get(index);
                LocalTime firstStart = asTime(first.get("startTime"), first.get("start_time"));
                LocalTime firstEnd = asTime(first.get("endTime"), first.get("end_time"));
                for (int other = index + 1; other < dayItems.size(); other++) {
                    Map<String, Object> second = dayItems.get(other);
                    LocalTime secondStart = asTime(second.get("startTime"), second.get("start_time"));
                    LocalTime secondEnd = asTime(second.get("endTime"), second.get("end_time"));
                    if (secondStart == null || secondEnd == null || firstStart == null || firstEnd == null) {
                        continue;
                    }
                    if (!secondStart.isBefore(firstEnd)) {
                        break;
                    }
                    if (!secondEnd.isAfter(firstStart)) {
                        continue;
                    }
                    String firstName = nameOf(first);
                    String secondName = nameOf(second);
                    conflicts.add(new Conflict(
                            entry.getKey(),
                            firstName,
                            secondName,
                            firstName + " 与 " + secondName + " 的时间安排重叠。"));
                }
            }
        }
        return conflicts;
    }

    private static String nameOf(Map<String, Object> item) {
        Object name = item.get("placeName");
        if (name == null) {
            name = item.get("place_name");
        }
        if (name == null) {
            name = item.get("placeId") != null ? item.get("placeId") : item.get("place_id");
        }
        if (name == null) {
            name = item.get("place_id");
        }
        return name == null ? "地点" : String.valueOf(name);
    }

    static LocalDate asDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        try {
            String text = String.valueOf(value);
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (RuntimeException error) {
            return null;
        }
    }

    static LocalTime asTime(Object primary, Object fallback) {
        Object value = primary != null ? primary : fallback;
        if (value == null) {
            return null;
        }
        if (value instanceof LocalTime time) {
            return time;
        }
        try {
            String text = String.valueOf(value);
            int space = text.lastIndexOf(' ') >= 0 ? text.lastIndexOf(' ') : text.lastIndexOf('T');
            if (space >= 0) {
                text = text.substring(space + 1);
            }
            return LocalTime.parse(text.length() == 5 ? text + ":00" : text);
        } catch (RuntimeException error) {
            return null;
        }
    }
}
