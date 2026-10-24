package com.huxizhen.travel.agent.tool;

import com.huxizhen.travel.integration.geo.DestinationResolver;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 推荐卡片的价格守卫。
 *
 * <p>模型/检索给出的价格必须过三关才允许进入预算：
 * <ol>
 *   <li><b>区间</b>：酒店 60–20000、餐厅 5–5000、门票 0–3000（人民币）。明显是外币数字时先按
 *       目的地汇率换算，再判断是否落在区间内；</li>
 *   <li><b>当地下限</b>：一份"53 元的东京寿司"不是便宜，是漏了币种。低于当地同类下限的价格
 *       会被抬到下限并标记 {@code price_adjusted}，同时文案说明"已调整"；</li>
 *   <li><b>免费</b>：资料里写"免费/免票/free"时价格归零并标 {@code is_free}。</li>
 * </ol>
 *
 * <p>当地价格带来自 {@link MealPriceData}（由国家 × 餐型/酒店/门票三张表组成），
 * 国家由 {@link DestinationResolver} 的离线表解析，解析不到时用币种反推。
 */
public final class PriceGuard {

    /** 价格上限（人民币）。超出的数字要么是外币，要么是模型编的。 */
    private static final Map<String, int[]> BOUNDS = Map.of(
            "hotel", new int[] {60, 20000},
            "restaurant", new int[] {5, 5000},
            "attraction", new int[] {0, 3000});

    private static final Map<String, Integer> DEFAULT_CNY =
            Map.of("hotel", 400, "restaurant", 80, "attraction", 100);

    private static final Map<String, String> CURRENCY_COUNTRIES = Map.ofEntries(
            Map.entry("JPY", "日本"), Map.entry("KRW", "韩国"), Map.entry("THB", "泰国"),
            Map.entry("USD", "美国"), Map.entry("GBP", "英国"), Map.entry("EUR", "法国"),
            Map.entry("HKD", "香港"), Map.entry("MOP", "澳门"), Map.entry("TWD", "台湾"),
            Map.entry("SGD", "新加坡"), Map.entry("MYR", "马来西亚"), Map.entry("VND", "越南"),
            Map.entry("IDR", "印度尼西亚"), Map.entry("PHP", "菲律宾"),
            Map.entry("AED", "阿拉伯联合酋长国"), Map.entry("AUD", "澳大利亚"),
            Map.entry("NZD", "新西兰"), Map.entry("CAD", "加拿大"), Map.entry("CHF", "瑞士"),
            Map.entry("TRY", "土耳其"), Map.entry("RUB", "俄罗斯"), Map.entry("EGP", "埃及"),
            Map.entry("MXN", "墨西哥"), Map.entry("BRL", "巴西"), Map.entry("INR", "印度"));

    private static final List<String> FREE_MARKERS =
            List.of("免费", "免票", "无需门票", "不需门票", "free", "no admission");

    private PriceGuard() {
    }

    /** 调整结果：价格、是否被抬到当地下限、给用户的说明。 */
    public record Adjusted(int price, boolean adjusted, String note) {
    }

    /** 目的地 -> 价格带用的国家名。离线表优先，其次用币种反推。 */
    public static String countryOf(String destination) {
        DestinationResolver.GeoPlace place = DestinationResolver.resolveOffline(destination);
        if (place != null && place.country() != null && !place.country().isBlank()) {
            return place.country();
        }
        String currency = CurrencyTool.currencyForDestination(destination);
        if (currency != null && !"CNY".equals(currency)) {
            return CURRENCY_COUNTRIES.getOrDefault(currency, "中国");
        }
        return "中国";
    }

    /** 把明显是外币的数字换算成人民币；换完仍不在区间内就返回 null（当作没有报价）。 */
    public static Integer plausible(Integer value, String category, String destination) {
        if (value == null || value < 0) {
            return null;
        }
        int[] bounds = BOUNDS.getOrDefault(category, new int[] {0, 100000});
        if (value >= bounds[0] && value <= bounds[1]) {
            return value;
        }
        String currency = CurrencyTool.currencyForDestination(destination);
        if (currency != null && !"CNY".equals(currency)) {
            int converted = CurrencyTool.toCny(value, currency);
            if (converted >= bounds[0] && converted <= bounds[1]) {
                return converted;
            }
        }
        return null;
    }

    /** 资料里说免费？ */
    public static boolean isFree(String name, String priceHint) {
        String text = ((name == null ? "" : name) + " " + (priceHint == null ? "" : priceHint))
                .toLowerCase(Locale.ROOT);
        for (String marker : FREE_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 没有报价时的目的地形状默认价（人民币）。 */
    public static int defaultPrice(
            String destination, String category, String name, String dish, String priceHint) {
        String country = countryOf(destination);
        PriceBand band = bandFor(country, category, name, dish, priceHint);
        if (band != null && band.typical() > 0) {
            return band.typical();
        }
        return DEFAULT_CNY.getOrDefault(category, 100);
    }

    /**
     * 低于当地同类下限时抬到下限并标记。门票不设下限：免费景点是真实存在的。
     */
    public static Adjusted adjust(
            int price, String category, String destination, String name, String dish, String priceHint) {
        if ("attraction".equals(category)) {
            return new Adjusted(price, false, null);
        }
        PriceBand band = bandFor(countryOf(destination), category, name, dish, priceHint);
        if (band == null) {
            return new Adjusted(price, false, null);
        }
        int floor = Math.max(1, band.low());
        if (price < floor) {
            return new Adjusted(
                    floor,
                    true,
                    "资料价低于当地同类常见人均，已按常见人均下限 " + floor + " 元估计");
        }
        return new Adjusted(price, false, null);
    }

    /** 价格档位（cheap/mid/splurge/over），与 Python 的 tier 语义一致。 */
    public static String tierOf(int price, Integer allowance) {
        if (allowance == null || allowance <= 0) {
            return "";
        }
        if (price <= allowance * 0.7) {
            return "cheap";
        }
        if (price <= allowance * 1.3) {
            return "mid";
        }
        if (price <= allowance * 2.5) {
            return "splurge";
        }
        return "over";
    }

    public static String tierLabel(String tier) {
        return switch (tier) {
            case "cheap" -> "省钱";
            case "mid" -> "适中";
            case "splurge" -> "值得奢侈";
            case "over" -> "超预算";
            default -> "";
        };
    }

    /** "超预算"的统一阈值：餐厅允许一档值得多花钱，酒店/门票不允许。 */
    public static int overBudgetCeiling(String category, int allowance) {
        return "restaurant".equals(category)
                ? (int) Math.round(allowance * 2.5)
                : (int) Math.round(allowance * 1.3);
    }

    static PriceBand bandFor(
            String country, String category, String name, String dish, String priceHint) {
        switch (category) {
            case "hotel" -> {
                return MealPriceData.HOTEL_BANDS.get(country);
            }
            case "attraction" -> {
                return MealPriceData.ATTRACTION_BANDS.get(country);
            }
            case "restaurant" -> {
                Map<String, PriceBand> bands = MealPriceData.MEAL_BANDS.get(country);
                if (bands == null) {
                    return null;
                }
                String kind = mealKind(name, dish, priceHint);
                PriceBand band = bands.get(kind);
                return band != null ? band : bands.get("generic");
            }
            default -> {
                return null;
            }
        }
    }

    /** 从菜名/招牌菜猜价格带的餐型；猜不到用 generic。 */
    public static String mealKind(String name, String dish, String priceHint) {
        String text = ((name == null ? "" : name) + " " + (dish == null ? "" : dish) + " "
                + (priceHint == null ? "" : priceHint)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "怀石", "omakase", "米其林", "法餐", "fine dining")) {
            return "fine_dining";
        }
        if (containsAny(text, "寿司", "刺身", "sushi")) {
            if (containsAny(text, "回转", "迴轉", "kaiten")) {
                return "sushi_kaiten";
            }
            if (containsAny(text, "高级", "高端", "fine")) {
                return "sushi_fine";
            }
            return "sushi_mid";
        }
        if (containsAny(text, "拉面", "拉麵", "面馆", "麵館", "noodle", "ramen", "乌冬", "荞麦")) {
            return "ramen_noodles";
        }
        if (containsAny(text, "烧肉", "烤肉", "bbq", "grill", "和牛", "牛排")) {
            return "bbq_grill";
        }
        if (containsAny(text, "火锅", "火鍋", "hotpot", "涮")) {
            return "hotpot";
        }
        if (containsAny(text, "海鲜", "海鮮", "seafood", "蟹", "龙虾")) {
            return "seafood";
        }
        if (containsAny(text, "居酒屋", "izakaya", "酒吧", "bar")) {
            return "izakaya_bar";
        }
        if (containsAny(text, "咖啡", "甜品", "蛋糕", "甜点", "cafe", "dessert", "茶室")) {
            return "cafe_dessert";
        }
        if (containsAny(text, "早餐", "包子", "粥", "早茶", "breakfast", "brunch")) {
            return "breakfast";
        }
        if (containsAny(text, "小吃", "夜市", "street", "摊", "路边")) {
            return "street_food";
        }
        if (containsAny(text, "快餐", "fast food", "汉堡", "炸鸡")) {
            return "fast_food";
        }
        if (containsAny(text, "本地菜", "家常", "local", "小馆", "食堂")) {
            return "casual_local";
        }
        return "generic";
    }

    private static boolean containsAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
