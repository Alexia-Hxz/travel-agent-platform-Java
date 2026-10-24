"""验证「API 返回的 image_url 能被浏览器真正取到」。

流程：注册临时账号 → 建行程 → 建一个带本地图片路径的地点 → 读回来 → 用返回的 URL
发一次 GET，确认是 200 且是图片 → 删掉行程和账号。

这是对"页面图裂"最直接的回归：数据里有路径不等于文件真的能访问。

用法：python tools/check_image_chain.py
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request
import uuid

API = "http://127.0.0.1:8001/api/v1"
ORIGIN = "http://127.0.0.1:8001"
IMAGE = "/static/place_images/cache/32945f0b4fa40fa4d9f0a40a7d0e0b91314cd898af9418b33c3eb39675aed4f0.jpg"


def call(method: str, path: str, body=None, token: str | None = None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"{API}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"null")


def main() -> int:
    suffix = uuid.uuid4().hex[:8]
    status, body = call("POST", "/auth/register",
                        {"identifier": f"imgcheck_{suffix}", "password": "pass1234"})
    if status != 201:
        print(f"注册失败：{status} {body}")
        return 1
    token = body["access_token"]

    try:
        status, trip = call("POST", "/trips", {
            "title": "图片链路自检", "destination": "南京",
            "start_date": "2026-12-01", "end_date": "2026-12-02",
        }, token=token)
        if status != 201:
            print(f"建行程失败：{status} {trip}")
            return 1
        trip_id = trip["id"]

        status, place = call("POST", f"/trips/{trip_id}/places", {
            "name": "图片自检地点", "type": "attraction", "address": "测试地址",
            "image_url": IMAGE,
        }, token=token)
        if status != 201:
            print(f"建地点失败：{status} {place}")
            return 1

        status, places = call("GET", f"/trips/{trip_id}/places", token=token)
        stored = (places or [{}])[0].get("image_url")
        print(f"API 返回 image_url: {stored}")
        if stored != IMAGE:
            print("image_url 与写入值不一致")
            return 1

        url = ORIGIN + stored
        try:
            with urllib.request.urlopen(url, timeout=15) as response:
                payload = response.read()
                content_type = response.headers.get("Content-Type", "")
                print(f"GET {url} -> {response.status} {content_type} {len(payload)}B")
                ok = response.status == 200 and content_type.startswith("image/") and len(payload) > 1000
        except urllib.error.HTTPError as error:
            print(f"GET {url} -> HTTP {error.code}")
            ok = False

        print("结论：" + ("图片链路通" if ok else "图片拿不到，需要检查 /static 映射与缓存目录"))
        return 0 if ok else 1
    finally:
        status, trips = call("GET", "/trips", token=token)
        for item in trips or []:
            if item.get("title") == "图片链路自检":
                call("DELETE", f"/trips/{item['id']}", token=token)


if __name__ == "__main__":
    sys.exit(main())
