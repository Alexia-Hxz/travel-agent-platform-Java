"""把 Python 版的数据表生成 Java 常量，避免手抄 23 国价格带 / 城市表 / 汇率。

来源（只读）：
    app/agents/tools/meal_price_data.py   MEAL_BANDS / HOTEL_BANDS / ATTRACTION_BANDS
    app/agents/tools/currency.py          RATES_TO_CNY / CURRENCY_LABELS
    app/services/destination_data.py      CITY_ROWS / DESTINATION_ALIASES / COUNTRY_PREFIXES

输出：
    agent/tool/MealPriceData.java
    agent/tool/CurrencyData.java
    integration/geo/DestinationData.java

用法：python tools/gen_java_data.py
"""

from __future__ import annotations

import re
from pathlib import Path

PY_BACKEND = Path(r"D:\FastAPI + Langgraph + RAG（Python）\travel-agent-platform\backend")
JAVA_ROOT = (
    Path(__file__).resolve().parent.parent
    / "backend"
    / "src"
    / "main"
    / "java"
    / "com"
    / "huxizhen"
    / "travel"
)


class Band:
    def __init__(self, low, typical, high):
        self.low, self.typical, self.high = low, typical, high


def load_module(path: Path, extra: dict | None = None) -> dict:
    """执行一个只含字面量的模块，剥掉对 app.* 的 import。"""
    source = path.read_text(encoding="utf-8")
    source = re.sub(r"^from app\.[^\n]*$", "", source, flags=re.M)
    namespace: dict = {}
    exec(compile(source, str(path), "exec"), {"__name__": "generated", **(extra or {})}, namespace)
    return namespace


def header(name: str, description: str) -> str:
    return (
        "package {package};\n\n"
        "// 由 tools/gen_java_data.py 从 Python 版数据表生成，请勿手改。\n"
        "// {description}\n\n"
    ).format(package="{package}", description=description)


def emit_meal() -> None:
    ns = load_module(
        PY_BACKEND / "app/agents/tools/meal_price_data.py",
        {"Band": Band, "_band": lambda low, typical, high: Band(low, typical, high)},
    )
    lines = [
        'package com.huxizhen.travel.agent.tool;',
        '',
        '// 由 tools/gen_java_data.py 从 Python 版 meal_price_data.py 生成，请勿手改。',
        '// 每个 band 都是「该国当地」的 CNY 单笔花费：人均一餐 / 每间夜 / 人均门票。',
        '',
        'import java.util.Map;',
        '',
        'public final class MealPriceData {',
        '',
        '    private MealPriceData() {',
        '    }',
        '',
    ]
    for const, comment, nested in (
        ("MEAL_BANDS", "国家 -> 餐型 -> 价格带（人均一餐）", True),
        ("HOTEL_BANDS", "国家 -> 价格带（每间夜）", False),
        ("ATTRACTION_BANDS", "国家 -> 价格带（人均门票）", False),
    ):
        table = ns[const]
        lines.append(f"    /** {comment} */")
        java_type = "Map<String, Map<String, PriceBand>>" if nested else "Map<String, PriceBand>"
        witness = "Map.<String, Map<String, PriceBand>>" if nested else "Map.<String, PriceBand>"
        lines.append(f"    public static final {java_type} {const} = {witness}ofEntries(")
        country_entries = []
        for country, value in table.items():
            if isinstance(value, Band):
                country_entries.append(
                    f'            Map.entry({java_string(country)}, band({value.low}, {value.typical}, {value.high}))'
                )
            else:
                kind_entries = [
                    f'                    Map.entry({java_string(kind)}, band({band.low}, {band.typical}, {band.high}))'
                    for kind, band in value.items()
                ]
                country_entries.append(
                    f'            Map.entry({java_string(country)}, Map.<String, PriceBand>ofEntries(\n'
                    + ",\n".join(kind_entries)
                    + "\n            ))"
                )
        lines.append(",\n".join(country_entries))
        lines.append("    );")
        lines.append("")
    lines += [
        "    private static PriceBand band(int low, int typical, int high) {",
        "        return new PriceBand(low, typical, high);",
        "    }",
        "}",
        "",
    ]
    write("agent/tool/MealPriceData.java", "\n".join(lines))
    print(f"MealPriceData: {len(ns['MEAL_BANDS'])} countries")


def emit_currency() -> None:
    ns = load_module(PY_BACKEND / "app/agents/tools/currency.py")
    rates = ns["RATES_TO_CNY"]
    labels = ns["CURRENCY_LABELS"]
    lines = [
        'package com.huxizhen.travel.agent.tool;',
        '',
        '// 由 tools/gen_java_data.py 从 Python 版 currency.py 生成，请勿手改。',
        '// 汇率基准日与原项目一致（RATES_AS_OF=2026-09）。',
        '',
        'import java.util.Map;',
        '',
        'public final class CurrencyData {',
        '',
        '    private CurrencyData() {',
        '    }',
        '',
        f'    public static final String RATES_AS_OF = {java_string(ns.get("RATES_AS_OF", "2026-09"))};',
        '',
        '    /** 1 单位外币 = 多少 CNY。 */',
        '    public static final Map<String, Double> RATES_TO_CNY = Map.ofEntries(',
        ",\n".join(
            f'            Map.entry({java_string(code)}, {value})' for code, value in rates.items()
        ),
        '    );',
        '',
        '    public static final Map<String, String> CURRENCY_LABELS = Map.ofEntries(',
        ",\n".join(
            f'            Map.entry({java_string(code)}, {java_string(label)})'
            for code, label in labels.items()
        ),
        '    );',
        '}',
        '',
    ]
    write("agent/tool/CurrencyData.java", "\n".join(lines))
    print(f"CurrencyData: {len(rates)} rates")


def emit_destination() -> None:
    ns = load_module(PY_BACKEND / "app/services/destination_data.py")
    rows = ns["CITY_ROWS"]
    aliases = ns["DESTINATION_ALIASES"]
    prefixes = ns["COUNTRY_PREFIXES"]
    lines = [
        'package com.huxizhen.travel.integration.geo;',
        '',
        '// 由 tools/gen_java_data.py 从 Python 版 destination_data.py 生成，请勿手改。',
        '// 每一项都是地理编码器真实返回过的答案，不是手敲的坐标。',
        '',
        'import java.util.Map;',
        '',
        'public final class DestinationData {',
        '',
        '    private DestinationData() {',
        '    }',
        '',
        '    /** 地理编码器认可的英文/当地写法 -> 城市行。 */',
        '    public record CityRow(double latitude, double longitude, String label, String country, String resolved) {',
        '    }',
        '',
        '    public static final Map<String, CityRow> CITY_ROWS = Map.ofEntries(',
        ",\n".join(
            '            Map.entry({key}, new CityRow({lat}, {lon}, {label}, {country}, {resolved}))'.format(
                key=java_string(key),
                lat=row[0],
                lon=row[1],
                label=java_string(row[2]),
                country=java_string(row[3]),
                resolved=java_string(row[4]),
            )
            for key, row in rows.items()
        ),
        '    );',
        '',
        '    /** 中文/别名 -> CITY_ROWS 的 key。 */',
        '    public static final Map<String, String> DESTINATION_ALIASES = Map.ofEntries(',
        ",\n".join(
            f'            Map.entry({java_string(name)}, {java_string(key)})' for name, key in aliases.items()
        ),
        '    );',
        '',
        '    /** 国家前缀 -> (去掉前缀后的名字, 国家名)。 */',
        '    public static final Map<String, String[]> COUNTRY_PREFIXES = Map.ofEntries(',
        ",\n".join(
            '            Map.entry({key}, new String[]{{{inner}, {country}}})'.format(
                key=java_string(key), inner=java_string(value[0]), country=java_string(value[1])
            )
            for key, value in prefixes.items()
        ),
        '    );',
        '}',
        '',
    ]
    write("integration/geo/DestinationData.java", "\n".join(lines))
    print(f"DestinationData: {len(rows)} cities, {len(aliases)} aliases")


def java_string(value: str) -> str:
    escaped = (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )
    return f'"{escaped}"'


def write(relative: str, content: str) -> None:
    target = JAVA_ROOT / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def main() -> int:
    emit_meal()
    emit_currency()
    emit_destination()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
