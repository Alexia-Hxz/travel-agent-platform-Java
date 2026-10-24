package com.huxizhen.travel.agent.tool;

/**
 * 一条现实价格带：便宜的、常见的、贵的。
 *
 * <p>数值永远是「该目的地当地」的 CNY：人均一餐 / 每间夜 / 人均门票，不是全国平均。
 */
public record PriceBand(int low, int typical, int high) {

    /** 把买不到一份完整餐食的价格抬到下限，其余不动。 */
    public int clampFloor(int price) {
        return Math.max(price, low);
    }

    public int clamp(int price) {
        return Math.max(low, Math.min(high, price));
    }
}
