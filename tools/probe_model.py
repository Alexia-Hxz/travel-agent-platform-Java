"""临时诊断：直连 DashScope，确认模型名/Key/请求体是否被接受。"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ENV = Path(__file__).resolve().parent.parent / "backend" / ".env"
for line in ENV.read_text(encoding="utf-8").splitlines():
    if "=" in line and not line.strip().startswith("#"):
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip())

BASE = os.environ.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
KEY = os.environ["DASHSCOPE_API_KEY"]


def post(body: dict) -> None:
    request = urllib.request.Request(
        f"{BASE}/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = json.loads(response.read())
            content = payload["choices"][0]["message"]["content"]
            print(f"  OK  model={body['model']} -> {content[:160]!r}")
    except urllib.error.HTTPError as error:
        print(f"  ERR model={body['model']} status={error.code} body={error.read()[:400]}")
    except Exception as error:  # noqa: BLE001
        print(f"  ERR model={body['model']} {type(error).__name__}: {error}")


def main() -> int:
    for model in (
        "qwen-vl-plus",
        "qwen-vl-ocr",
        "qwen2.5-vl-7b-instruct",
        "qwen2.5-vl-72b-instruct",
        "qwen3-vl-plus",
        "qwen3-vl-30b-a3b-instruct",
        "qwen3-vl-235b-a22b-instruct",
    ):
        post({"model": model, "messages": [{"role": "user", "content": "say ok"}], "max_tokens": 10})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
