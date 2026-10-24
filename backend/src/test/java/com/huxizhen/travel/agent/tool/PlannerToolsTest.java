package com.huxizhen.travel.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.huxizhen.travel.dto.plan.PlanDtos;
import com.huxizhen.travel.service.AutoScheduleService;
import com.huxizhen.travel.service.DocumentSplitter;
import com.huxizhen.travel.entity.Place;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 纯算法部分的回归：冲突、切分、自动排期、天气建议。 */
class PlannerToolsTest {

    // ------------------------------------------------------------ conflicts

    @Test
    void overlappingItemsOnSameDayConflict() {
        List<Map<String, Object>> items = List.of(
                item("2026-11-01", "09:00:00", "12:00:00", "夫子庙"),
                item("2026-11-01", "11:00:00", "13:00:00", "博物院"));

        List<ConflictDetector.Conflict> conflicts = ConflictDetector.detect(items);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).message()).isEqualTo("夫子庙 与 博物院 的时间安排重叠。");
    }

    @Test
    void adjacentItemsDoNotConflict() {
        List<Map<String, Object>> items = List.of(
                item("2026-11-01", "09:00:00", "12:00:00", "夫子庙"),
                item("2026-11-01", "12:00:00", "13:00:00", "博物院"));

        assertThat(ConflictDetector.detect(items)).isEmpty();
    }

    @Test
    void sameTimesOnDifferentDaysDoNotConflict() {
        List<Map<String, Object>> items = List.of(
                item("2026-11-01", "09:00:00", "12:00:00", "夫子庙"),
                item("2026-11-02", "09:00:00", "12:00:00", "博物院"));

        assertThat(ConflictDetector.detect(items)).isEmpty();
    }

    // ------------------------------------------------------------- splitter

    @Test
    void splitterKeepsWholeLinesTogether() {
        String text = String.join("\n", "订单号：HTL123", "入住 2026-11-01", "退房 2026-11-04");

        List<DocumentSplitter.Piece> pieces = DocumentSplitter.split(text);

        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).text()).isEqualTo(text);
    }

    @Test
    void splitterCarriesALineAcrossTheBoundary() {
        // 目标 30：第一片装满后，末尾那一行会重新出现在下一片开头
        String text = "a".repeat(20) + "\n" + "b".repeat(20) + "\n" + "c".repeat(20);

        List<DocumentSplitter.Piece> pieces = DocumentSplitter.split(text, 30, 25);

        assertThat(pieces).hasSizeGreaterThan(1);
        // 第二片以 a、b 两行结尾、第三片以 b 开头：末行被带到了下一片
        assertThat(pieces.get(1).text()).endsWith("b".repeat(20));
        assertThat(pieces.get(2).text()).startsWith("b".repeat(20));
    }

    @Test
    void splitterSplitsAPathologicallyLongLine() {
        String text = "x".repeat(200);

        List<DocumentSplitter.Piece> pieces = DocumentSplitter.split(text, 60, 20);

        assertThat(pieces).hasSizeGreaterThan(1);
        assertThat(pieces.get(0).text()).hasSize(60);
        assertThat(pieces.get(0).charStart()).isZero();
    }

    @Test
    void splitterDropsZeroWidthCharacters() {
        List<DocumentSplitter.Piece> pieces = DocumentSplitter.split("订\u200b单\u200d号：A1");

        assertThat(pieces.get(0).text()).isEqualTo("订单号：A1");
    }

    @Test
    void splitterOnBlankTextReturnsNothing() {
        assertThat(DocumentSplitter.split("   \n\n  ")).isEmpty();
    }

    // ----------------------------------------------------------- auto schedule

    @Test
    void autoScheduleHonoursPreferredDate() {
        AutoScheduleService service = new AutoScheduleService();
        Place place = place("博物院", "attraction", null, LocalDate.parse("2026-11-03"));

        AutoScheduleService.Proposal proposal = service.propose(
                List.of(place), List.of(), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04"));

        assertThat(proposal.proposed()).hasSize(1);
        assertThat(proposal.proposed().get(0).date()).isEqualTo("2026-11-03");
        assertThat(proposal.proposed().get(0).note()).contains("按规划建议放在这一天");
        assertThat(proposal.proposed().get(0).startTime()).isEqualTo("09:00:00");
    }

    @Test
    void autoScheduleRespectsOpeningHours() {
        AutoScheduleService service = new AutoScheduleService();
        Place place = place("夜游", "attraction", "19:00-22:00", null);

        AutoScheduleService.Proposal proposal = service.propose(
                List.of(place), List.of(), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"));

        assertThat(proposal.proposed().get(0).startTime()).isEqualTo("19:00:00");
        assertThat(proposal.proposed().get(0).note()).contains("按开放时间 19:00-21:00");
    }

    @Test
    void autoScheduleReportsPlacesItCannotFit() {
        AutoScheduleService service = new AutoScheduleService();
        Place first = place("A", "attraction", null, null);
        Place second = place("B", "attraction", null, null);
        // 一天只有 09:00-21:00；两个 2 小时的项目放得下，但先占 12 小时再放第三个就放不下了
        List<AutoScheduleService.ExistingSlot> existing = new java.util.ArrayList<>();
        for (int hour = 9; hour <= 19; hour++) {
            existing.add(new AutoScheduleService.ExistingSlot(
                    100L + hour, LocalDate.parse("2026-11-01"),
                    LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0)));
        }

        AutoScheduleService.Proposal proposal = service.propose(
                List.of(first, second), existing,
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"));

        assertThat(proposal.proposed()).isEmpty();
        assertThat(proposal.skipped()).hasSize(2);
        assertThat(proposal.skipped().get(0).reason()).contains("没有足够空闲时段");
    }

    // -------------------------------------------------------------- weather

    @Test
    void weatherAdvisorFlagsOutdoorItemsOnWetDays() {
        Map<String, Object> weather = new LinkedHashMap<>();
        weather.put("status", "completed");
        weather.put("days", List.of(Map.of(
                "date", "2026-11-02",
                "indoor_priority", true,
                "condition", "降雨或恶劣天气",
                "precipitation_probability", 80)));

        List<Map<String, Object>> dailyPlan = List.of(Map.of(
                "date", "2026-11-02",
                "items", List.of(Map.of("place_name", "玄武湖公园"), Map.of("place_name", "南京博物院"))));

        List<PlanDtos.WeatherAdvice> advice = WeatherAdvisor.build(
                "南京", weather, dailyPlan, List.of());

        assertThat(advice).hasSize(1);
        assertThat(advice.get(0).outdoorItems()).containsExactly("玄武湖公园");
        assertThat(advice.get(0).indoorItems()).containsExactly("南京博物院");
        assertThat(advice.get(0).message()).contains("降水概率 80%");
        assertThat(advice.get(0).suggestion()).contains("玄武湖公园");
    }

    @Test
    void weatherAdvisorStaysSilentOnGoodDays() {
        Map<String, Object> weather = new LinkedHashMap<>();
        weather.put("status", "completed");
        weather.put("days", List.of(Map.of("date", "2026-11-02", "indoor_priority", false)));

        assertThat(WeatherAdvisor.build("南京", weather, List.of(), List.of())).isEmpty();
    }

    @Test
    void weatherAdjustmentRequestIsReadyToSend() {
        PlanDtos.WeatherAdvice advice = new PlanDtos.WeatherAdvice(
                "2026-11-02", "降雨或恶劣天气", 80, true,
                List.of("玄武湖公园"), List.of("玄武湖公园"), List.of(),
                "2026-11-02 预计降雨或恶劣天气（降水概率 80%）", "换到别的日期");

        String request = WeatherAdvisor.adjustmentRequest("南京", List.of(advice));

        assertThat(request).startsWith("请根据天气调整我在南京的行程：");
        assertThat(request).contains("- 2026-11-02：");
        assertThat(request).endsWith("请保持其他日期的安排不变，并重新计算预算。");
    }

    // --------------------------------------------------------------- helpers

    private static Map<String, Object> item(String date, String start, String end, String name) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("date", date);
        item.put("start_time", start);
        item.put("end_time", end);
        item.put("place_name", name);
        return item;
    }

    private static Place place(String name, String type, String openingHours, LocalDate preferred) {
        Place place = new Place();
        place.setId((long) name.hashCode());
        place.setName(name);
        place.setType(type);
        place.setOpeningHours(openingHours);
        place.setPreferredDate(preferred);
        return place;
    }
}
