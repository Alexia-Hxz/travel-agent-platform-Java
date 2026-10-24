"""诊断 Pexels 搜索与图片下载在本机是否可用。"""

from __future__ import annotations

import json
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / "backend" / ".env"


def read_env() -> dict[str, str]:
    values: dict[str, str] = {}
    for line in ENV_FILE.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


ENV = read_env()
KEY = ENV.get("PEXELS_API_KEY", "")
print(f"PEXELS_API_KEY: {'已配置' if KEY else '缺失'}（长度 {len(KEY)}）")
print(f"IMAGE_HOST_FALLBACKS: {ENV.get('IMAGE_HOST_FALLBACKS', '(空)')}")

query = "mapo tofu food"
url = f"https://api.pexels.com/v1/search?query={urllib.parse.quote(query)}&per_page=3&orientation=landscape"
try:
    request = urllib.request.Request(url, headers={"Authorization": KEY,
                                                   "User-Agent": "travel-agent-platform/1.0"})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.loads(response.read())
    photos = payload.get("photos", [])
    print(f"搜索接口: HTTP {response.status}，返回 {len(photos)} 张")
    for photo in photos[:2]:
        print("  ", (photo.get("src") or {}).get("large"))
    sample = (photos[0].get("src") or {}).get("large") if photos else None
except Exception as error:  # noqa: BLE001
    print(f"搜索接口失败: {type(error).__name__}: {error}")
    sample = None

if sample:
    try:
        request = urllib.request.Request(sample, headers={"User-Agent": "Mozilla/5.0",
                                                          "Range": "bytes=0-65535"})
        with urllib.request.urlopen(request, timeout=8) as response:
            print(f"直连图片: HTTP {response.status} {response.headers.get('Content-Type')}")
    except Exception as error:  # noqa: BLE001
        print(f"直连图片失败: {type(error).__name__}: {error}")

    for ip in ("104.18.66.220", "104.18.67.220", "172.64.147.160"):
        result = subprocess.run(
            ["curl.exe", "-s", "-o", "NUL", "-w", "%{http_code} %{content_type}", "--max-time", "10",
             "--resolve", f"images.pexels.com:443:{ip}", sample],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        print(f"curl --resolve {ip}: {result.stdout.decode(errors='replace')}")
