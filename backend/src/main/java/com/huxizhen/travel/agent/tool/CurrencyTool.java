package com.huxizhen.travel.agent.tool;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 币种、汇率与「这个目的地大概什么价位」。
 *
 * <p>数据来自 {@link CurrencyData} / {@link MealPriceData}，两者都是由
 * {@code tools/gen_java_data.py} 从 Python 版生成的，不是手抄。
 */
public final class CurrencyTool {

    private CurrencyTool() {
    }

    /** 关键词 -> 币种。按顺序匹配，先命中先用（与原实现的 marker 表同序）。 */
    private static final List<Map.Entry<String, String>> DESTINATION_MARKERS = List.of(
            Map.entry("日本", "JPY"), Map.entry("东京", "JPY"), Map.entry("東京", "JPY"),
            Map.entry("大阪", "JPY"), Map.entry("京都", "JPY"), Map.entry("北海道", "JPY"),
            Map.entry("札幌", "JPY"), Map.entry("冲绳", "JPY"), Map.entry("名古屋", "JPY"),
            Map.entry("福冈", "JPY"), Map.entry("tokyo", "JPY"), Map.entry("osaka", "JPY"),
            Map.entry("kyoto", "JPY"), Map.entry("japan", "JPY"),
            Map.entry("韩国", "KRW"), Map.entry("首尔", "KRW"), Map.entry("釜山", "KRW"),
            Map.entry("济州", "KRW"), Map.entry("korea", "KRW"), Map.entry("seoul", "KRW"),
            Map.entry("泰国", "THB"), Map.entry("曼谷", "THB"), Map.entry("清迈", "THB"),
            Map.entry("普吉", "THB"), Map.entry("苏梅", "THB"), Map.entry("thailand", "THB"),
            Map.entry("bangkok", "THB"),
            Map.entry("美国", "USD"), Map.entry("纽约", "USD"), Map.entry("洛杉矶", "USD"),
            Map.entry("旧金山", "USD"), Map.entry("拉斯维加斯", "USD"), Map.entry("夏威夷", "USD"),
            Map.entry("西雅图", "USD"), Map.entry("波士顿", "USD"), Map.entry("usa", "USD"),
            Map.entry("new york", "USD"), Map.entry("los angeles", "USD"),
            Map.entry("英国", "GBP"), Map.entry("伦敦", "GBP"), Map.entry("爱丁堡", "GBP"),
            Map.entry("uk", "GBP"), Map.entry("london", "GBP"),
            Map.entry("法国", "EUR"), Map.entry("巴黎", "EUR"), Map.entry("意大利", "EUR"),
            Map.entry("罗马", "EUR"), Map.entry("米兰", "EUR"), Map.entry("威尼斯", "EUR"),
            Map.entry("佛罗伦萨", "EUR"), Map.entry("德国", "EUR"), Map.entry("柏林", "EUR"),
            Map.entry("慕尼黑", "EUR"), Map.entry("西班牙", "EUR"), Map.entry("巴塞罗那", "EUR"),
            Map.entry("马德里", "EUR"), Map.entry("荷兰", "EUR"), Map.entry("阿姆斯特丹", "EUR"),
            Map.entry("葡萄牙", "EUR"), Map.entry("里斯本", "EUR"), Map.entry("希腊", "EUR"),
            Map.entry("雅典", "EUR"), Map.entry("圣托里尼", "EUR"), Map.entry("paris", "EUR"),
            Map.entry("europe", "EUR"),
            Map.entry("香港", "HKD"), Map.entry("hong kong", "HKD"),
            Map.entry("澳门", "MOP"), Map.entry("macau", "MOP"),
            Map.entry("台湾", "TWD"), Map.entry("台北", "TWD"), Map.entry("高雄", "TWD"),
            Map.entry("taiwan", "TWD"), Map.entry("taipei", "TWD"),
            Map.entry("新加坡", "SGD"), Map.entry("singapore", "SGD"),
            Map.entry("马来西亚", "MYR"), Map.entry("吉隆坡", "MYR"), Map.entry("沙巴", "MYR"),
            Map.entry("malaysia", "MYR"),
            Map.entry("越南", "VND"), Map.entry("河内", "VND"), Map.entry("胡志明", "VND"),
            Map.entry("岘港", "VND"), Map.entry("vietnam", "VND"),
            Map.entry("印尼", "IDR"), Map.entry("巴厘岛", "IDR"), Map.entry("雅加达", "IDR"),
            Map.entry("indonesia", "IDR"),
            Map.entry("菲律宾", "PHP"), Map.entry("马尼拉", "PHP"), Map.entry("长滩岛", "PHP"),
            Map.entry("philippines", "PHP"),
            Map.entry("迪拜", "AED"), Map.entry("阿联酋", "AED"), Map.entry("阿布扎比", "AED"),
            Map.entry("dubai", "AED"),
            Map.entry("澳大利亚", "AUD"), Map.entry("澳洲", "AUD"), Map.entry("悉尼", "AUD"),
            Map.entry("墨尔本", "AUD"), Map.entry("布里斯班", "AUD"), Map.entry("australia", "AUD"),
            Map.entry("新西兰", "NZD"), Map.entry("奥克兰", "NZD"), Map.entry("皇后镇", "NZD"),
            Map.entry("new zealand", "NZD"),
            Map.entry("加拿大", "CAD"), Map.entry("温哥华", "CAD"), Map.entry("多伦多", "CAD"),
            Map.entry("蒙特利尔", "CAD"), Map.entry("canada", "CAD"),
            Map.entry("瑞士", "CHF"), Map.entry("苏黎世", "CHF"), Map.entry("日内瓦", "CHF"),
            Map.entry("因特拉肯", "CHF"), Map.entry("switzerland", "CHF"),
            Map.entry("土耳其", "TRY"), Map.entry("伊斯坦布尔", "TRY"),
            Map.entry("卡帕多奇亚", "TRY"), Map.entry("turkey", "TRY"),
            Map.entry("俄罗斯", "RUB"), Map.entry("莫斯科", "RUB"), Map.entry("圣彼得堡", "RUB"),
            Map.entry("russia", "RUB"),
            Map.entry("埃及", "EGP"), Map.entry("开罗", "EGP"), Map.entry("egypt", "EGP"),
            Map.entry("墨西哥", "MXN"), Map.entry("坎昆", "MXN"), Map.entry("mexico", "MXN"),
            Map.entry("巴西", "BRL"), Map.entry("里约", "BRL"), Map.entry("brazil", "BRL"),
            Map.entry("印度", "INR"), Map.entry("新德里", "INR"), Map.entry("孟买", "INR"),
            Map.entry("india", "INR"));

    private static final List<String> CNY_MARKERS = List.of(
            "中国", "国内", "北京", "上海", "南京", "杭州", "成都", "广州", "深圳", "西安",
            "重庆", "苏州", "武汉", "长沙", "厦门", "青岛", "天津", "昆明", "大理", "丽江",
            "三亚", "桂林", "哈尔滨", "沈阳", "济南", "郑州", "合肥", "福州", "南宁", "贵阳",
            "兰州", "乌鲁木齐", "拉萨", "呼和浩特", "银川", "西宁", "石家庄", "太原", "南昌",
            "宁波", "无锡", "常州", "珠海", "佛山", "东莞", "温州", "绍兴", "扬州", "镇江");

    /** 目的地 -> ISO 币种；不认识返回 {@code null}（按人民币处理）。 */
    public static String currencyForDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        String text = destination.toLowerCase(Locale.ROOT);
        for (String marker : CNY_MARKERS) {
            if (text.contains(marker.toLowerCase(Locale.ROOT))) {
                return "CNY";
            }
        }
        for (Map.Entry<String, String> entry : DESTINATION_MARKERS) {
            if (text.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static boolean isForeign(String destination) {
        String currency = currencyForDestination(destination);
        return currency != null && !"CNY".equals(currency);
    }

    public static double rateToCny(String currency) {
        if (currency == null) {
            return 1.0;
        }
        return CurrencyData.RATES_TO_CNY.getOrDefault(currency.toUpperCase(Locale.ROOT), 1.0);
    }

    public static int toCny(double amount, String currency) {
        return (int) Math.round(amount * rateToCny(currency));
    }

    public static String labelOf(String currency) {
        return CurrencyData.CURRENCY_LABELS.getOrDefault(currency, currency);
    }

    /** 给提示词的一句话：这趟旅行在当地什么价位。 */
    public static String promptHint(String destination) {
        String currency = currencyForDestination(destination);
        if (currency == null || "CNY".equals(currency)) {
            return "价格一律以人民币（CNY）给出。";
        }
        return "这是出境旅行（当地货币 " + currency + "，" + labelOf(currency) + "）。"
                + "资料里的当地价格要换算成人民币：1 " + currency + " ≈ "
                + rateToCny(currency) + " CNY。最终输出仍用人民币。";
    }
}
