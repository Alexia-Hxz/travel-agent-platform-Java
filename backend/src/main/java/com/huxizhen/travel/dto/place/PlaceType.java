package com.huxizhen.travel.dto.place;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 地点类型。对外一律小写，与 Pydantic 的 {@code PlaceType} 一致。 */
public enum PlaceType {
    ATTRACTION("attraction"),
    HOTEL("hotel"),
    RESTAURANT("restaurant"),
    AIRPORT("airport"),
    STATION("station");

    private final String value;

    PlaceType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static PlaceType fromValue(String value) {
        if (value != null) {
            for (PlaceType type : values()) {
                if (type.value.equals(value.trim().toLowerCase())) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("Unknown place type: " + value);
    }

    /** 数据库里存的是小写字符串。 */
    public static PlaceType fromDatabase(String value) {
        return value == null ? null : fromValue(value);
    }
}
