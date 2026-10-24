"""修好迁移后丢失的地点图片。

症状：行程里的地点卡片只显示分类小图标，没有照片。
原因：旧库 `places.image_url` 存的是 `http://127.0.0.1:8001/static/place_images/cache/<hash>.jpg`，
      这些缓存文件还在 Python 项目的 `app/static/place_images/cache/` 里，Java 这边没有，
      请求 404，前端就退回占位图。

做三件事：
1. 把 Python 的缓存文件复制到 Java 的 `backend/data/photo-cache/`（同名，sha256 命名）。
2. 把数据库里指向旧绝对地址的 URL 改成相对路径 `/static/...`，这样换端口/换主机也不坏。
3. 剩下的 https 外链（Pexels / TheMealDB）尽力镜像到本地；下载失败就保留原链接。

用法：python tools/restore_place_images.py [--dry-run]
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PY_CACHE = Path(
    r"D:\FastAPI + Langgraph + RAG（Python）\travel-agent-platform\backend\app\static\place_images\cache"
)
JAVA_CACHE = ROOT / "backend" / "data" / "photo-cache"
ENV_FILE = ROOT / "backend" / ".env"

ALLOWED_HOSTS = {"images.pexels.com", "www.themealdb.com"}
IMAGE_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}
MAX_BYTES = 2_000_000


def read_env() -> dict[str, str]:
    values: dict[str, str] = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip()
    return values


def mysql(sql: str) -> str:
    env = read_env()
    command = [
        "mysql",
        "--default-character-set=utf8mb4",
        "-N",
        "-h", "127.0.0.1",
        "-P", "3306",
        "-u", env.get("DB_USERNAME", "root"),
        env.get("DB_NAME", "travel_agent"),
    ]
    if env.get("DB_PASSWORD"):
        command.insert(1, "-p" + env["DB_PASSWORD"])
    result = subprocess.run(command, input=sql.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise SystemExit(result.stderr.decode("utf-8", "replace"))
    return result.stdout.decode("utf-8", "replace")


def copy_cache(dry_run: bool) -> int:
    if not PY_CACHE.is_dir():
        print(f"  跳过：找不到 Python 缓存目录 {PY_CACHE}")
        return 0
    if not dry_run:
        JAVA_CACHE.mkdir(parents=True, exist_ok=True)
    copied = 0
    for source in PY_CACHE.iterdir():
        if not source.is_file() or source.name.startswith("."):
            continue
        target = JAVA_CACHE / source.name
        if target.exists() and target.stat().st_size == source.stat().st_size:
            continue
        if not dry_run:
            shutil.copy2(source, target)
        copied += 1
    print(f"  复制缓存图片 {copied} 个（已存在且大小相同的跳过）")
    return copied


def normalize_urls(dry_run: bool) -> int:
    prefixes = ("http://127.0.0.1:8001", "http://localhost:8001")
    changed = 0
    for prefix in prefixes:
        count = mysql(
            f"SELECT COUNT(*) FROM places WHERE image_url LIKE '{prefix}/%';"
        ).strip()
        changed += int(count or 0)
        if not dry_run and count.strip() != "0":
            mysql(
                "UPDATE places SET image_url = REPLACE(image_url, "
                f"'{prefix}', '') WHERE image_url LIKE '{prefix}/%';"
            )
    print(f"  改写成相对路径 {changed} 条")
    return changed


def mirror_remote(dry_run: bool) -> int:
    rows = mysql("SELECT id, image_url FROM places WHERE image_url LIKE 'https://%';")
    mirrored = 0
    for line in rows.splitlines():
        if "\t" not in line:
            continue
        place_id, url = line.split("\t", 1)
        url = url.strip()
        host = url.split("/")[2] if "//" in url else ""
        if host not in ALLOWED_HOSTS:
            continue
        digest = hashlib.sha256(url.encode("utf-8")).hexdigest()
        existing = next((JAVA_CACHE / f"{digest}{ext}" for ext in IMAGE_TYPES.values()
                         if (JAVA_CACHE / f"{digest}{ext}").is_file()), None)
        if existing is not None:
            local = f"/static/place_images/cache/{existing.name}"
        else:
            local = download(url, digest) if not dry_run else None
            if local is None:
                continue
        if not dry_run:
            mysql(
                "UPDATE places SET image_url = '{}' WHERE id = {};".format(
                    local.replace("'", "''"), int(place_id)
                )
            )
            os_lines = local
            del os_lines
        mirrored += 1
    print(f"  镜像外链图片 {mirrored} 条")
    return mirrored


def download(url: str, digest: str) -> str | None:
    request = urllib.request.Request(url, headers={"User-Agent": "travel-agent-platform/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            if response.status != 200:
                return None
            content_type = (response.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
            extension = IMAGE_TYPES.get(content_type)
            if extension is None:
                return None
            payload = response.read(MAX_BYTES + 1)
            if not payload or len(payload) > MAX_BYTES:
                return None
            JAVA_CACHE.mkdir(parents=True, exist_ok=True)
            target = JAVA_CACHE / f"{digest}{extension}"
            target.write_bytes(payload)
            return f"/static/place_images/cache/{target.name}"
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    print(f"Python 缓存：{PY_CACHE}")
    print(f"Java 缓存：  {JAVA_CACHE}")
    copy_cache(args.dry_run)
    normalize_urls(args.dry_run)
    mirror_remote(args.dry_run)

    remaining = mysql(
        "SELECT COUNT(*) total,"
        " SUM(image_url IS NULL) nulls,"
        " SUM(image_url LIKE '/static/%') local_paths,"
        " SUM(image_url LIKE 'https://%') remote"
        " FROM places;"
    ).strip()
    print(f"  最终：总计/空/本地/外链 = {remaining}")
    if args.dry_run:
        print("（dry-run，未写入）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
