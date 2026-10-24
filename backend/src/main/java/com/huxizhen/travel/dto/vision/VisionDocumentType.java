package com.huxizhen.travel.dto.vision;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;

/** 图片被判定的单据类型。 */
public enum VisionDocumentType {
    ATTRACTION_PHOTO("attraction_photo"),
    HOTEL_BOOKING("hotel_booking"),
    TRANSPORT_TICKET("transport_ticket"),
    MENU("menu"),
    TRAVEL_GUIDE("travel_guide"),
    OTHER("other");

    /** 模型偶尔回中文，原实现有一张别名表。 */
    private static final Map<String, VisionDocumentType> ALIASES = Map.of(
            "景点", ATTRACTION_PHOTO,
            "景点照片", ATTRACTION_PHOTO,
            "酒店", HOTEL_BOOKING,
            "酒店订单", HOTEL_BOOKING,
            "酒店预订", HOTEL_BOOKING,
            "交通票", TRANSPORT_TICKET,
            "交通票据", TRANSPORT_TICKET,
            "菜单", MENU,
            "旅行指南", TRAVEL_GUIDE);

    private final String value;

    VisionDocumentType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static VisionDocumentType fromValue(String value) {
        return from(value);
    }

    public static VisionDocumentType from(String value) {
        if (value == null) {
            return OTHER;
        }
        String normalized = value.strip().toLowerCase();
        for (VisionDocumentType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        return ALIASES.getOrDefault(value.strip(), OTHER);
    }
}
