package com.huxizhen.travel.dto.vision;

import java.time.LocalDate;

/** 图片里识别出的一个时段建议。时间保持字符串（{@code HH:mm}），与模型输出一致。 */
public record VisionItinerarySuggestion(
        LocalDate date, String startTime, String endTime, String note) {
}
