package com.huxizhen.travel.agent.tool;

// 由 tools/gen_java_data.py 从 Python 版 currency.py 生成，请勿手改。
// 汇率基准日与原项目一致（RATES_AS_OF=2026-09）。

import java.util.Map;

public final class CurrencyData {

    private CurrencyData() {
    }

    public static final String RATES_AS_OF = "2026-09";

    /** 1 单位外币 = 多少 CNY。 */
    public static final Map<String, Double> RATES_TO_CNY = Map.ofEntries(
            Map.entry("CNY", 1.0),
            Map.entry("JPY", 0.048),
            Map.entry("USD", 7.1),
            Map.entry("EUR", 7.75),
            Map.entry("GBP", 9.05),
            Map.entry("KRW", 0.0051),
            Map.entry("HKD", 0.91),
            Map.entry("MOP", 0.88),
            Map.entry("TWD", 0.22),
            Map.entry("SGD", 5.3),
            Map.entry("MYR", 1.6),
            Map.entry("THB", 0.2),
            Map.entry("VND", 0.00028),
            Map.entry("IDR", 0.00045),
            Map.entry("PHP", 0.125),
            Map.entry("INR", 0.085),
            Map.entry("AED", 1.93),
            Map.entry("AUD", 4.65),
            Map.entry("NZD", 4.3),
            Map.entry("CAD", 5.2),
            Map.entry("CHF", 8.0),
            Map.entry("TRY", 0.21),
            Map.entry("RUB", 0.078),
            Map.entry("EGP", 0.145),
            Map.entry("MXN", 0.38),
            Map.entry("BRL", 1.3),
            Map.entry("ZAR", 0.39)
    );

    public static final Map<String, String> CURRENCY_LABELS = Map.ofEntries(
            Map.entry("JPY", "日元"),
            Map.entry("USD", "美元"),
            Map.entry("EUR", "欧元"),
            Map.entry("GBP", "英镑"),
            Map.entry("KRW", "韩元"),
            Map.entry("HKD", "港币"),
            Map.entry("MOP", "澳门元"),
            Map.entry("TWD", "新台币"),
            Map.entry("SGD", "新加坡元"),
            Map.entry("MYR", "马来西亚林吉特"),
            Map.entry("THB", "泰铢"),
            Map.entry("VND", "越南盾"),
            Map.entry("IDR", "印尼盾"),
            Map.entry("PHP", "菲律宾比索"),
            Map.entry("INR", "印度卢比"),
            Map.entry("AED", "迪拉姆"),
            Map.entry("AUD", "澳元"),
            Map.entry("NZD", "新西兰元"),
            Map.entry("CAD", "加元"),
            Map.entry("CHF", "瑞士法郎"),
            Map.entry("TRY", "里拉"),
            Map.entry("RUB", "卢布"),
            Map.entry("EGP", "埃及镑"),
            Map.entry("MXN", "墨西哥比索"),
            Map.entry("BRL", "雷亚尔"),
            Map.entry("ZAR", "兰特"),
            Map.entry("CNY", "人民币")
    );
}
