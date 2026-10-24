"""把 Python 版 SQLite 数据库原样搬到 Java 版的 MySQL 里。

要点：
* **保留原表 id**。行程、地点、行程项靠 id 关联，重新编号会让所有外键错位。
* **保留密码哈希原文**。格式是 `scrypt$n$r$p$salt$hash`，Java 侧用同样的算法校验，
  所以老账号用原密码就能登录。
* 目标库先清空这 6 张表（不删表结构），再整表灌入，最后把 AUTO_INCREMENT 推到 max+1。
* 上传文件（`data/uploads`）没有行就不复制；有行时按 `file_path` 一起搬。

用法：
    python tools/migrate_sqlite.py                 # 执行迁移
    python tools/migrate_sqlite.py --dry-run       # 只打印统计与生成的 SQL 行数
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SQLITE = Path(r"D:\FastAPI + Langgraph + RAG（Python）\travel-agent-platform\backend\travel_agent.db")
ENV_FILE = ROOT / "backend" / ".env"

# 按外键依赖顺序：先删子表，再删父表；插入反过来。
TABLES = [
    "itinerary_adjustments",
    "itinerary_items",
    "places",
    "trip_files",
    "trips",
    "users",
]

COLUMNS = {
    "users": [
        "id", "username", "email", "phone", "password_hash", "display_name", "created_at",
        "last_login_at", "email_verified_at", "phone_verified_at", "failed_login_count",
    ],
    "trips": [
        "id", "user_id", "title", "destination", "start_date", "end_date", "budget",
        "adult_male_count", "adult_female_count", "child_male_count", "child_female_count",
        "created_at",
    ],
    "places": [
        "id", "trip_id", "name", "type", "address", "latitude", "longitude", "image_url",
        "rating", "price_level", "opening_hours", "source_url", "note", "estimated_cost",
        "quantity", "preferred_date",
    ],
    "itinerary_items": [
        "id", "trip_id", "date", "start_time", "end_time", "place_id", "note", "sort_order",
    ],
    "itinerary_adjustments": [
        "id", "trip_id", "instruction", "proposal_json", "status", "created_at", "applied_at",
    ],
    "trip_files": [
        "id", "trip_id", "original_filename", "stored_filename", "content_type", "file_size",
        "file_path", "parse_status", "parsed_text", "parsed_result", "parse_error", "parsed_at",
        "created_at", "index_status", "index_error", "indexed_chunks", "indexed_at",
    ],
}


def read_env() -> dict[str, str]:
    values: dict[str, str] = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip()
    return values


def fetch_mysql_config() -> dict[str, str]:
    env = read_env()
    url = env.get("DB_URL", "")
    host, port, database = "127.0.0.1", "3306", "travel_agent"
    if url.startswith("jdbc:mysql://"):
        authority = url[len("jdbc:mysql://"):].split("/", 1)
        host_port = authority[0].split("?", 1)[0]
        if ":" in host_port:
            host, port = host_port.split(":", 1)
        else:
            host = host_port
        if len(authority) > 1:
            database = authority[1].split("?", 1)[0] or database
    return {
        "host": env.get("DB_HOST", host),
        "port": env.get("DB_PORT", port),
        "database": env.get("DB_NAME", database),
        "user": env.get("DB_USERNAME", "root"),
        "password": env.get("DB_PASSWORD", ""),
    }


def quote(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, (int, float)):
        return str(value)
    text = str(value)
    text = (
        text.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\0", "\\0")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\x1a", "\\Z")
    )
    return f"'{text}'"


# 旧库把图片存成了指向 Python 后端的绝对地址。搬过来以后主机/端口都可能变，
# 统一改写成相对路径，前端会自己拼上当前后端地址。
OLD_IMAGE_PREFIXES = (
    "http://127.0.0.1:8001",
    "http://localhost:8001",
)


def clean_value(table: str, column: str, value):
    if table == "places" and column == "image_url" and isinstance(value, str):
        for prefix in OLD_IMAGE_PREFIXES:
            if value.startswith(prefix + "/"):
                return value[len(prefix):]
    return value


def build_sql(sqlite_path: Path, config: dict[str, str]) -> tuple[str, dict[str, int]]:
    connection = sqlite3.connect(f"file:{sqlite_path}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    counts: dict[str, int] = {}
    statements: list[str] = [
        "SET NAMES utf8mb4;",
        "SET FOREIGN_KEY_CHECKS = 0;",
    ]
    for table in TABLES:
        statements.append(f"DELETE FROM `{table}`;")
    statements.append("SET FOREIGN_KEY_CHECKS = 1;")

    for table in reversed(TABLES):
        rows = list(connection.execute(f'SELECT * FROM "{table}" ORDER BY id'))
        counts[table] = len(rows)
        if not rows:
            continue
        columns = [c for c in COLUMNS[table] if c in rows[0].keys()]
        column_sql = ", ".join(f"`{c}`" for c in columns)
        for row in rows:
            values = ", ".join(
                quote(clean_value(table, c, row[c])) for c in columns
            )
            statements.append(f"INSERT INTO `{table}` ({column_sql}) VALUES ({values});")
        statements.append(
            f"ALTER TABLE `{table}` AUTO_INCREMENT = {max(r['id'] for r in rows) + 1};"
        )

    connection.close()
    return "\n".join(statements) + "\n", counts


def run_mysql(sql_text: str, config: dict[str, str]) -> None:
    command = [
        "mysql",
        "--default-character-set=utf8mb4",
        "-h", config["host"],
        "-P", config["port"],
        "-u", config["user"],
        config["database"],
    ]
    if config["password"]:
        command.insert(1, f"-p{config['password']}")
    result = subprocess.run(
        command,
        input=sql_text.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stderr.decode("utf-8", "replace"))
        raise SystemExit(f"mysql 执行失败（退出码 {result.returncode}）")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sqlite", default=str(DEFAULT_SQLITE))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    sqlite_path = Path(args.sqlite)
    if not sqlite_path.exists():
        raise SystemExit(f"找不到 SQLite 数据库：{sqlite_path}")

    config = fetch_mysql_config()
    sql_text, counts = build_sql(sqlite_path, config)

    print(f"源库：{sqlite_path}")
    print(f"目标：mysql://{config['host']}:{config['port']}/{config['database']}")
    for table, count in counts.items():
        print(f"  {table:<24} {count} 行")
    print(f"生成 SQL：{len(sql_text.splitlines())} 行")

    if args.dry_run:
        print("（dry-run，未执行）")
        return 0

    run_mysql(sql_text, config)
    print("迁移完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
