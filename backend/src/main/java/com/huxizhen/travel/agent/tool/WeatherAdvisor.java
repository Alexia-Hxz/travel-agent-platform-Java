package com.huxizhen.travel.agent.tool;

import com.huxizhen.travel.dto.plan.PlanDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把天气预报变成行程能执行的动作。
 *
 * <p>天气服务已经逐日给出 {@code indoor_priority}，但没人用它：下雨天照样显示同样的行程、
 * 没有任何提示，用户得自己发现矛盾。这里把"那天的预报"和"那天实际排了什么"配对，
 * 指出可能受影响的项目，并给出一句可以照着做的建议。
 *
 * <p>关键词只是一条**提示**（中文地名很描述性），不是判决；所以文案永远是"建议确认"。
 */
public final class WeatherAdvisor {

    private static final String[] OUTDOOR_KEYWORDS = {
        "公园", "山", "陵", "湖", "寺", "庙", "塔", "江", "河", "海", "沙滩", "长城", "园林",
        "湿地", "岛", "桥", "谷", "洞", "温泉", "古镇", "步行街", "广场", "街", "苑", "园"
    };

    private static final String[] INDOOR_KEYWORDS = {
        "博物馆", "博物院", "美术馆", "展览", "陈列馆", "纪念馆", "科技馆", "图书馆", "书店",
        "水族馆", "剧院", "影院", "电影", "商场", "购物", "室内", "温泉馆"
    };

    public static final String OUTDOOR = "outdoor";
    public static final String INDOOR = "indoor";
    public static final String UNKNOWN = "unknown";

    private WeatherAdvisor() {
    }

    /** 室内关键词先判：像「西湖美术馆」同时含"湖"和"美术馆"，它是室内的。 */
    public static String classifyPlace(String name, String placeType) {
        String text = name == null ? "" : name.strip();
        if (text.isEmpty()) {
            return UNKNOWN;
        }
        for (String keyword : INDOOR_KEYWORDS) {
            if (text.contains(keyword)) {
                return INDOOR;
            }
        }
        if (placeType != null && List.of("hotel", "restaurant", "airport", "station").contains(placeType)) {
            return INDOOR;
        }
        for (String keyword : OUTDOOR_KEYWORDS) {
            if (text.contains(keyword)) {
                return OUTDOOR;
            }
        }
        return UNKNOWN;
    }

    /**
     * 每个"天气威胁到行程"的日子给一条可执行建议；天气好的日子**不出现**，
     * 所以空列表意味着"没什么可担心的"，而不是"没有信息"。
     */
    public static List<PlanDtos.WeatherAdvice> build(
            String destination,
            Map<String, Object> weather,
            List<Map<String, Object>> dailyPlan,
            List<Map<String, Object>> places) {
        if (weather == null || !"completed".equals(weather.get("status"))) {
            return List.of();
        }
        Map<String, String> placeTypes = new LinkedHashMap<>();
        for (Map<String, Object> place : places) {
            Object name = place.get("name");
            if (name != null) {
                placeTypes.put(String.valueOf(name), place.get("type") == null ? null : String.valueOf(place.get("type")));
            }
        }
        Map<String, List<String>> scheduledByDate = itemsByDate(dailyPlan);

        List<PlanDtos.WeatherAdvice> advice = new ArrayList<>();
        Object rawDays = weather.get("days");
        if (!(rawDays instanceof List<?> days)) {
            return advice;
        }
        for (Object rawDay : days) {
            if (!(rawDay instanceof Map<?, ?> day)) {
                continue;
            }
            if (!Boolean.TRUE.equals(day.get("indoor_priority"))) {
                continue;
            }
            String dayDate = day.get("date") == null ? "" : String.valueOf(day.get("date"));
            List<String> scheduled = scheduledByDate.getOrDefault(dayDate, List.of());
            List<String> outdoor = new ArrayList<>();
            List<String> indoor = new ArrayList<>();
            for (String name : scheduled) {
                String kind = classifyPlace(name, placeTypes.get(name));
                if (OUTDOOR.equals(kind)) {
                    outdoor.add(name);
                } else if (INDOOR.equals(kind)) {
                    indoor.add(name);
                }
            }
            Object precipitation = day.get("precipitation_probability");
            String condition = day.get("condition") == null
                    ? "降雨或恶劣天气"
                    : String.valueOf(day.get("condition"));

            StringBuilder message = new StringBuilder(dayDate + " 预计" + condition);
            if (precipitation != null) {
                message.append("（降水概率 ").append(precipitation).append("%）");
            }
            String suggestion;
            if (scheduled.isEmpty()) {
                message.append("当天还没有安排行程，建议优先选择室内景点，并留意临近出发的天气更新。");
                suggestion = "把室内的博物馆、美术馆或商场安排在这一天，户外的行程留到天气更好的日期。";
            } else if (!outdoor.isEmpty()) {
                message.append("当天已安排：").append(String.join("、", scheduled))
                        .append("。其中 ").append(String.join("、", outdoor))
                        .append(" 偏户外，雨天体验会明显受影响。");
                suggestion = "把 " + String.join("、", outdoor) + " 换到天气较好的日期，当天改为"
                        + (indoor.isEmpty()
                                ? "室内的博物馆、美术馆或商场"
                                : String.join("、", indoor) + " 等室内安排")
                        + "。";
            } else {
                message.append("当天已安排：").append(String.join("、", scheduled))
                        .append("，看起来都在室内，整体不受影响。");
                suggestion = "当天安排基本不受影响，出发前再确认一次天气即可。";
            }

            advice.add(new PlanDtos.WeatherAdvice(
                    dayDate,
                    condition,
                    precipitation == null ? null : ((Number) precipitation).intValue(),
                    true,
                    scheduled,
                    outdoor,
                    indoor,
                    message.toString(),
                    suggestion));
        }
        return advice;
    }

    /** 给"对话式调整"准备好的一句请求，用户点一下就能把这段话发过去。 */
    public static String adjustmentRequest(String destination, List<PlanDtos.WeatherAdvice> advice) {
        if (advice == null || advice.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("请根据天气调整我在" + destination + "的行程：");
        for (PlanDtos.WeatherAdvice entry : advice) {
            lines.add("- " + entry.date() + "：" + entry.message());
            lines.add("  建议：" + entry.suggestion());
        }
        lines.add("请保持其他日期的安排不变，并重新计算预算。");
        return String.join("\n", lines);
    }

    private static Map<String, List<String>> itemsByDate(List<Map<String, Object>> dailyPlan) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> day : dailyPlan) {
            String date = String.valueOf(day.getOrDefault("date", ""));
            List<String> names = new ArrayList<>();
            Object items = day.get("items");
            if (items instanceof List<?> list) {
                for (Object rawItem : list) {
                    if (rawItem instanceof Map<?, ?> item) {
                        Object name = item.get("place_name");
                        if (name == null) {
                            name = item.get("placeName");
                        }
                        if (name != null && !String.valueOf(name).isBlank()) {
                            names.add(String.valueOf(name).strip());
                        }
                    }
                }
            }
            if (!date.isBlank()) {
                grouped.put(date, names);
            }
        }
        return grouped;
    }
}
