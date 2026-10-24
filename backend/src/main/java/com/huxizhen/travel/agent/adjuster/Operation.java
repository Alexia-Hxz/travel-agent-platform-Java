package com.huxizhen.travel.agent.adjuster;

import com.huxizhen.travel.dto.place.PlaceCreate;
import com.huxizhen.travel.dto.place.PlaceType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条行程调整操作。
 *
 * <p>这是草案的原子单位：前端逐条勾选，apply 时按勾选的索引写入。
 *
 * <p>序列化刻意保留 Python 的键顺序与"缺省即省略"语义：{@code budget_amount} 为 null 时
 * **不写进 JSON**（其它字段即使为 null 也写），因为前端按这个形状渲染预算行。
 */
public record Operation(
        String kind,
        Long itemId,
        Long placeId,
        PlaceCreate place,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        String note,
        int sortOrder,
        Integer budgetAmount) {

    public static final List<String> KINDS = List.of(
            "remove_item", "add_item", "add_place_and_item", "set_budget", "update_item");

    /** 形状校验，对应 Pydantic 的 {@code validate_shape}。 */
    public void validate() {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("未知的操作类型：" + kind);
        }
        if ("remove_item".equals(kind) && itemId == null) {
            throw new IllegalArgumentException("remove_item requires item_id");
        }
        if ("add_item".equals(kind) && placeId == null) {
            throw new IllegalArgumentException("add_item requires place_id");
        }
        if ("add_place_and_item".equals(kind) && place == null) {
            throw new IllegalArgumentException("add_place_and_item requires place");
        }
        if ("update_item".equals(kind) && itemId == null) {
            throw new IllegalArgumentException("update_item requires item_id");
        }
        if ("set_budget".equals(kind)) {
            if (budgetAmount == null) {
                throw new IllegalArgumentException("set_budget requires budget_amount");
            }
            if (itemId != null || placeId != null || place != null || date != null
                    || startTime != null || endTime != null) {
                throw new IllegalArgumentException("set_budget only accepts budget_amount");
            }
        } else if (!"remove_item".equals(kind)) {
            if (date == null || startTime == null || endTime == null) {
                throw new IllegalArgumentException("itinerary operations require date and times");
            }
        }
        if (startTime != null && endTime != null && endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("end_time cannot be earlier than start_time");
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind);
        map.put("item_id", itemId);
        map.put("place_id", placeId);
        map.put("place", place == null ? null : placeToMap(place));
        map.put("date", date == null ? null : date.toString());
        map.put("start_time", startTime == null ? null : startTime.toString());
        map.put("end_time", endTime == null ? null : endTime.toString());
        map.put("note", note);
        map.put("sort_order", sortOrder);
        if (budgetAmount != null) {
            map.put("budget_amount", budgetAmount);
        }
        return map;
    }

    /** 从数据库/请求里的 Map 还原；字段全缺时返回 null（交给上游报 422）。 */
    public static Operation fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        Operation operation = new Operation(
                text(raw.get("kind")),
                longOrNull(raw.get("item_id"), raw.get("itemId")),
                longOrNull(raw.get("place_id"), raw.get("placeId")),
                placeFrom(raw.get("place")),
                dateOrNull(raw.get("date")),
                timeOrNull(raw.get("start_time"), raw.get("startTime")),
                timeOrNull(raw.get("end_time"), raw.get("endTime")),
                text(raw.get("note")),
                intOrZero(raw.get("sort_order"), raw.get("sortOrder")),
                intOrNull(raw.get("budget_amount"), raw.get("budgetAmount")));
        operation.validate();
        return operation;
    }

    static Map<String, Object> placeToMap(PlaceCreate place) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", place.name());
        map.put("type", place.type() == null ? null : place.type().value());
        map.put("address", place.address());
        map.put("latitude", place.latitude());
        map.put("longitude", place.longitude());
        map.put("image_url", place.imageUrl());
        map.put("rating", place.rating());
        map.put("price_level", place.priceLevel());
        map.put("opening_hours", place.openingHours());
        map.put("source_url", place.sourceUrl());
        map.put("note", place.note());
        map.put("estimated_cost", place.estimatedCost());
        map.put("quantity", place.quantity());
        map.put("preferred_date", place.preferredDate() == null ? null : place.preferredDate().toString());
        return map;
    }

    static PlaceCreate placeFrom(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String name = text(map.get("name"));
        String address = text(map.get("address"));
        if (name == null || address == null) {
            return null;
        }
        PlaceType type;
        try {
            type = PlaceType.fromValue(text(map.get("type")));
        } catch (RuntimeException error) {
            type = PlaceType.ATTRACTION;
        }
        return new PlaceCreate(
                name,
                type,
                address,
                doubleOrNull(map.get("latitude")),
                doubleOrNull(map.get("longitude")),
                text(map.get("image_url")),
                doubleOrNull(map.get("rating")),
                intOrNull(map.get("price_level"), null),
                text(map.get("opening_hours")),
                text(map.get("source_url")),
                text(map.get("note")),
                intOrNull(map.get("estimated_cost"), null),
                intOrNull(map.get("quantity"), null),
                dateOrNull(map.get("preferred_date")));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static Long longOrNull(Object primary, Object fallback) {
        Object value = primary != null ? primary : fallback;
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Integer intOrNull(Object primary, Object fallback) {
        Object value = primary != null ? primary : fallback;
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static int intOrZero(Object primary, Object fallback) {
        Integer value = intOrNull(primary, fallback);
        return value == null ? 0 : value;
    }

    private static Double doubleOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static LocalDate dateOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            String text = String.valueOf(value);
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static LocalTime timeOrNull(Object primary, Object fallback) {
        Object value = primary != null ? primary : fallback;
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            String text = String.valueOf(value);
            return LocalTime.parse(text.length() == 5 ? text + ":00" : text);
        } catch (RuntimeException error) {
            return null;
        }
    }
}
