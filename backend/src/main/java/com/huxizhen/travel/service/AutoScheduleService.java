package com.huxizhen.travel.service;

import com.huxizhen.travel.dto.itinerary.ProposedItineraryItem;
import com.huxizhen.travel.dto.itinerary.SkippedPlace;
import com.huxizhen.travel.entity.Place;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 给还没排期的收藏地点找一个时段。
 *
 * <p>逐行对齐 Python 的 {@code app/services/auto_schedule.py}：
 * <ul>
 *   <li>规划日窗 09:00–21:00，30 分钟步进；</li>
 *   <li>地点自己的营业时间优先，落在日窗之外时按它自己的窗口排（晚间游船）；</li>
 *   <li>{@code preferredDate}（推荐面板给的那一天）优先，排满才往后挪；</li>
 *   <li>放不下的地点**报告原因**，而不是悄悄丢掉。</li>
 * </ul>
 *
 * <p>只读：返回建议，调用方仍走正常的行程项接口落库。
 */
@Service
public class AutoScheduleService {

    static final LocalTime DAY_START = LocalTime.of(9, 0);
    static final LocalTime DAY_END = LocalTime.of(21, 0);

    private static final Map<String, Duration> DURATIONS = Map.of(
            "attraction", Duration.ofHours(2),
            "restaurant", Duration.ofMinutes(90),
            "hotel", Duration.ofHours(1),
            "airport", Duration.ofHours(1),
            "station", Duration.ofHours(1));
    private static final Duration DEFAULT_DURATION = Duration.ofHours(2);

    private static final Pattern TIME_RANGE = Pattern.compile(
            "(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*(?:-|–|—|~|～|至|到)\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})");

    /** 已排进行程的时段，用于避让。 */
    public record ExistingSlot(Long placeId, LocalDate date, LocalTime startTime, LocalTime endTime) {
    }

    /** 提议结果。 */
    public record Proposal(
            List<ProposedItineraryItem> proposed,
            List<SkippedPlace> skipped,
            List<String> alreadyScheduled) {
    }

    public Proposal propose(
            List<Place> places, List<ExistingSlot> existing, LocalDate startDate, LocalDate endDate) {
        List<ProposedItineraryItem> proposed = new ArrayList<>();
        List<SkippedPlace> skipped = new ArrayList<>();
        List<String> alreadyScheduled = new ArrayList<>();
        List<String> days = tripDays(startDate, endDate);
        if (days.isEmpty()) {
            return new Proposal(proposed, skipped, alreadyScheduled);
        }

        Map<String, List<LocalTime[]>> busy = busyBlocks(existing);
        java.util.Set<Long> scheduledIds = new java.util.HashSet<>();
        for (ExistingSlot slot : existing) {
            scheduledIds.add(slot.placeId());
        }

        for (Place place : places) {
            Long placeId = place.getId();
            String name = place.getName() == null ? "" : place.getName().strip();
            if (placeId == null || name.isEmpty()) {
                continue;
            }
            if (scheduledIds.contains(placeId)) {
                alreadyScheduled.add(name);
                continue;
            }

            Duration duration = DURATIONS.getOrDefault(
                    place.getType() == null ? "" : place.getType(), DEFAULT_DURATION);
            LocalTime[] opening = parseOpeningWindow(place.getOpeningHours());
            LocalTime[] window = planningWindow(opening);
            String openingNote = opening != null
                    ? "按开放时间 " + formatShort(window[0]) + "-" + formatShort(window[1]) + " 自动安排"
                    : "自动安排（无开放时间信息）";

            String preferred = preferredDay(place.getPreferredDate(), days);
            List<String> searchOrder = new ArrayList<>();
            if (preferred != null) {
                searchOrder.add(preferred);
            }
            for (String day : days) {
                if (!day.equals(preferred)) {
                    searchOrder.add(day);
                }
            }

            String placedDay = null;
            LocalTime[] placedSlot = null;
            for (String day : searchOrder) {
                LocalTime[] slot = slotForDay(day, duration, window[0], window[1], busy, 30);
                if (slot != null) {
                    placedDay = day;
                    placedSlot = slot;
                    break;
                }
            }

            if (placedDay != null && placedSlot != null) {
                String note;
                if (preferred != null && !placedDay.equals(preferred)) {
                    note = openingNote + "；建议的 " + preferred + " 已排满，改排到这一天";
                } else if (preferred != null) {
                    note = openingNote + "；按规划建议放在这一天";
                } else {
                    note = openingNote;
                }
                proposed.add(new ProposedItineraryItem(
                        placeId, name, placedDay, format(placedSlot[0]), format(placedSlot[1]), note));
                continue;
            }

            String reason = opening != null
                    ? "这几天在开放时间（" + formatShort(window[0]) + "-" + formatShort(window[1])
                            + "）内没有足够空闲时段，请手动安排或减少同时段的项目。"
                    : "这几天没有足够空闲时段，请手动安排或减少同时段的项目。";
            skipped.add(new SkippedPlace(placeId, name, reason));
        }
        return new Proposal(proposed, skipped, alreadyScheduled);
    }

    /** 读 "09:00-17:00" 及其变体；读不出来返回 null，让调用方回落到整个日窗。 */
    LocalTime[] parseOpeningWindow(String openingHours) {
        if (openingHours == null || openingHours.isBlank()) {
            return null;
        }
        Matcher matcher = TIME_RANGE.matcher(openingHours);
        if (!matcher.find()) {
            return null;
        }
        try {
            LocalTime start = LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            LocalTime end = LocalTime.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
            if (!end.isAfter(start)) {
                // "22:00-02:00" 跨夜，日窗模型表达不了
                return null;
            }
            return new LocalTime[] {start, end};
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 地点可被安排的区间：营业时间夹在日窗内；完全落在日窗外时按它自己的窗口。 */
    LocalTime[] planningWindow(LocalTime[] opening) {
        if (opening == null) {
            return new LocalTime[] {DAY_START, DAY_END};
        }
        LocalTime lower = opening[0].isAfter(DAY_START) ? opening[0] : DAY_START;
        LocalTime upper = opening[1].isBefore(DAY_END) ? opening[1] : DAY_END;
        if (!upper.isAfter(lower)) {
            return opening;
        }
        return new LocalTime[] {lower, upper};
    }

    private List<String> tripDays(LocalDate start, LocalDate end) {
        List<String> days = new ArrayList<>();
        if (start == null || end == null || end.isBefore(start)) {
            return days;
        }
        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            days.add(cursor.toString());
        }
        return days;
    }

    private Map<String, List<LocalTime[]>> busyBlocks(List<ExistingSlot> existing) {
        Map<String, List<LocalTime[]>> busy = new LinkedHashMap<>();
        for (ExistingSlot slot : existing) {
            if (slot.date() == null || slot.startTime() == null || slot.endTime() == null) {
                continue;
            }
            busy.computeIfAbsent(slot.date().toString(), key -> new ArrayList<>())
                    .add(new LocalTime[] {slot.startTime(), slot.endTime()});
        }
        busy.values().forEach(blocks -> blocks.sort(
                Comparator.comparing((LocalTime[] block) -> block[0]).thenComparing(block -> block[1])));
        return busy;
    }

    private boolean fits(LocalTime candidateStart, Duration duration, List<LocalTime[]> busy) {
        LocalTime candidateEnd = candidateStart.plus(duration);
        for (LocalTime[] block : busy) {
            if (candidateStart.isBefore(block[1]) && block[0].isBefore(candidateEnd)) {
                return false;
            }
        }
        return true;
    }

    private LocalTime[] slotForDay(
            String day,
            Duration duration,
            LocalTime windowStart,
            LocalTime windowEnd,
            Map<String, List<LocalTime[]>> busy,
            int stepMinutes) {
        List<LocalTime[]> dayBusy = new ArrayList<>(busy.getOrDefault(day, List.of()));
        LocalTime cursor = windowStart;
        while (true) {
            LocalTime candidateEnd = cursor.plus(duration);
            if (candidateEnd.isAfter(windowEnd)) {
                return null;
            }
            if (fits(cursor, duration, dayBusy)) {
                dayBusy.add(new LocalTime[] {cursor, candidateEnd});
                dayBusy.sort(Comparator.comparing((LocalTime[] block) -> block[0]));
                // 立刻写回：否则下一个地点会搜到同一段空闲时间，被安排到同一个时段
                busy.put(day, dayBusy);
                return new LocalTime[] {cursor, candidateEnd};
            }
            cursor = cursor.plusMinutes(stepMinutes);
        }
    }

    private static String preferredDay(LocalDate preferredDate, List<String> days) {
        if (preferredDate == null) {
            return null;
        }
        String value = preferredDate.toString();
        return days.contains(value) ? value : null;
    }

    private static String format(LocalTime time) {
        return String.format("%02d:%02d:%02d", time.getHour(), time.getMinute(), time.getSecond());
    }

    private static String formatShort(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}
