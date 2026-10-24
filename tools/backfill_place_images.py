"""给「没有图片的非酒店地点」补图。

背景：Python 版给每个餐厅/景点都配了图（酒店刻意不配）。迁移后仍有 16 个非酒店地点是
NULL（当时图片那一趟没抓到）。这个脚本按原项目的做法补上：

1. 让模型给每个地点生成一个**英文**检索词（图库是按英文索引的；中文名直接搜会搜到别处）；
2. Pexels 搜索（横向构图，15 个候选）；
3. 逐个候选做可达性探测（真的取前 64KB，4-6 秒超时），第一个能用的就下载；
4. 存进 `backend/data/photo-cache/`（`/static/place_images/cache/**` 映射的目录），
   数据库里写相对路径 `/static/place_images/cache/<sha256>.jpg`。

酒店一律跳过：原项目的设计就是"一张别的酒店的照片不提供任何信息"。

用法：
    python tools/backfill_place_images.py                # 补齐
    python tools/backfill_place_images.py --dry-run      # 只看会补哪些
    python tools/backfill_place_images.py --limit 5      # 先试 5 个
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / "backend" / ".env"
CACHE = ROOT / "backend" / "data" / "photo-cache"

PEXELS_URL = "https://api.pexels.com/v1/search"
IMAGE_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}
MAX_BYTES = 2_000_000
HINTS = {"restaurant": "food", "attraction": "landmark", "airport": "airport", "station": "station"}

PROMPT = (
    "你是旅行图片检索助手。给你一批地点（中文名、类型、城市），请为每个地点给出一个**英文**的"
    "图库检索词，2-5 个单词，用来在 Pexels 找一张合适的照片。\n"
    "要求：\n"
    "- 景点/地标：用该地点的英文专名（例如 \"Nanjing Museum\"、\"Sensoji Temple\"）；不确定就用地标类型 + 城市。\n"
    "- 餐厅：优先用它的招牌菜或菜系的英文（例如 \"mapo tofu\"、\"ramen bowl\"、\"dim sum\"），不要用店名。\n"
    "- 不要出现引号、标点、中文。\n"
    "只输出 JSON：{\"items\":[{\"id\":1,\"query\":\"...\"}]}"
)


def read_env() -> dict[str, str]:
    values: dict[str, str] = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip()
    return values


ENV = read_env()


def mysql(sql: str) -> str:
    command = [
        "mysql", "--default-character-set=utf8mb4", "-N",
        "-h", "127.0.0.1", "-P", "3306",
        "-u", ENV.get("DB_USERNAME", "root"),
        ENV.get("DB_NAME", "travel_agent"),
    ]
    if ENV.get("DB_PASSWORD"):
        command.insert(1, "-p" + ENV["DB_PASSWORD"])
    result = subprocess.run(command, input=sql.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise SystemExit(result.stderr.decode("utf-8", "replace"))
    return result.stdout.decode("utf-8", "replace")


def http_json(url: str, headers: dict[str, str], body: dict | None = None) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    # GET 时不带 Content-Type：Pexels 对带 JSON Content-Type 的 GET 会返回 400。
    # User-Agent 必须有：Pexels 会直接 403 掉默认的 "Python-urllib/3.x"。
    default_headers = {"User-Agent": "travel-agent-platform/1.0"}
    if data is not None:
        default_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers={**default_headers, **headers})
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.loads(response.read())


def english_queries(places: list[tuple[int, str, str, str]]) -> dict[int, str]:
    """让模型把中文地点名变成英文检索词；失败就回落到「城市 + 类别词」。"""
    api_key = ENV.get("DASHSCOPE_API_KEY", "")
    model = ENV.get("PLANNING_MODEL_NAME") or "qwen3.7-plus"
    if not api_key or not places:
        return {}
    payload = [
        {"id": pid, "name": name, "type": kind, "city": city} for pid, name, kind, city in places
    ]
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": PROMPT},
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
        ],
        "response_format": {"type": "json_object"},
        "temperature": 0,
    }
    try:
        response = http_json(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            {"Authorization": f"Bearer {api_key}"},
            body,
        )
        content = response["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        items = parsed.get("items", parsed if isinstance(parsed, list) else [])
        result: dict[int, str] = {}
        for item in items:
            try:
                pid = int(item["id"])
                query = str(item["query"]).strip()
            except (KeyError, TypeError, ValueError):
                continue
            if query:
                result[pid] = query[:120]
        return result
    except Exception as error:  # noqa: BLE001
        print(f"  [提示] 英文检索词生成失败，改用备用检索：{error}")
        return {}


def pexels_search(query: str) -> list[str]:
    api_key = ENV.get("PEXELS_API_KEY", "")
    if not api_key:
        return []
    url = f"{PEXELS_URL}?query={urllib.parse.quote(query)}&per_page=15&orientation=landscape"
    try:
        payload = http_json(url, {"Authorization": api_key})
    except Exception as error:  # noqa: BLE001
        print(f"  [提示] Pexels 搜索失败（{query}）：{type(error).__name__}: {error}")
        return []
    urls: list[str] = []
    for photo in payload.get("photos", []):
        for key in ("large", "landscape", "medium", "small"):
            candidate = (photo.get("src") or {}).get(key)
            if candidate:
                urls.append(candidate)
                break
    return urls


def probe(url: str) -> bool:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (compatible; travel-agent-platform/1.0)",
            "Range": "bytes=0-65535",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=6) as response:
            content_type = (response.headers.get("Content-Type") or "").lower()
            response.read(65536)
            return response.status in (200, 206) and content_type.startswith("image/")
    except Exception:  # noqa: BLE001
        return False


def download(url: str) -> str | None:
    request = urllib.request.Request(url, headers={"User-Agent": "travel-agent-platform/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            content_type = (response.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
            extension = IMAGE_TYPES.get(content_type)
            if extension is None or response.status != 200:
                return None
            payload = response.read(MAX_BYTES + 1)
            if not payload or len(payload) > MAX_BYTES:
                return None
    except Exception:  # noqa: BLE001
        return None

    CACHE.mkdir(parents=True, exist_ok=True)
    name = hashlib.sha256(url.encode("utf-8")).hexdigest() + extension
    (CACHE / name).write_bytes(payload)
    return f"/static/place_images/cache/{name}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    if not ENV.get("PEXELS_API_KEY"):
        print("没有 PEXELS_API_KEY，无法补图。请先在 backend/.env 里填上。")
        return 1

    rows = mysql(
        "SELECT p.id, p.name, p.type, t.destination FROM places p"
        " JOIN trips t ON t.id = p.trip_id"
        " WHERE p.type <> 'hotel' AND p.image_url IS NULL"
        " ORDER BY p.id;"
    )
    places: list[tuple[int, str, str, str]] = []
    for line in rows.splitlines():
        parts = line.split("\t")
        if len(parts) >= 4:
            places.append((int(parts[0]), parts[1], parts[2], parts[3]))

    if args.limit:
        places = places[: args.limit]
    print(f"待补图：{len(places)} 个非酒店地点")
    if not places:
        return 0

    queries = english_queries(places)
    filled = 0
    for pid, name, kind, city in places:
        base = queries.get(pid) or f"{city} {HINTS.get(kind, 'landmark')}"
        query = f"{base} {HINTS.get(kind, '')}".strip()
        if args.dry_run:
            print(f"  [dry] {name}（{kind}，{city}）-> 检索词：{query}")
            continue
        chosen = None
        for candidate in pexels_search(query)[:6]:
            if probe(candidate):
                chosen = download(candidate)
                if chosen:
                    break
        if not chosen:
            print(f"  [跳过] {name}：没有可用图片（检索词 {query}）")
            continue
        mysql(f"UPDATE places SET image_url = '{chosen}' WHERE id = {pid};")
        filled += 1
        print(f"  [已补] {name} -> {chosen}")

    if args.dry_run:
        print("（dry-run，未写库）")
        return 0

    remaining = mysql(
        "SELECT COUNT(*) FROM places WHERE type <> 'hotel' AND image_url IS NULL;"
    ).strip()
    print(f"\n本次补图 {filled} 个；非酒店地点仍缺图 {remaining} 个；酒店保持无图。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
