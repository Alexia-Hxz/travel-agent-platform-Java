"""旅迹 AI · Java 后端接口冒烟测试（只依赖标准库）。

用法（后端已启动）：
    python tools/api_smoke.py [base_url]

默认 base_url = http://127.0.0.1:8001/api/v1
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8001/api/v1"
ROOT = Path(__file__).resolve().parent.parent

PASSED = 0
FAILED: list[str] = []


def call(method: str, path: str, body: Any = None, token: str | None = None,
         raw: bytes | None = None, content_type: str | None = None):
    url = f"{BASE}{path}"
    data = raw
    headers: dict[str, str] = {}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            payload = response.read()
            parsed = json.loads(payload) if payload else None
            return response.status, parsed, dict(response.headers)
    except urllib.error.HTTPError as error:
        payload = error.read()
        try:
            parsed = json.loads(payload) if payload else None
        except json.JSONDecodeError:
            parsed = payload.decode("utf-8", "replace")
        return error.code, parsed, dict(error.headers)


def call_raw(method: str, path: str, token: str | None = None) -> tuple[int, bytes, dict]:
    request = urllib.request.Request(f"{BASE}{path}", method=method)
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers)


def call_no_redirect(path: str) -> tuple[int, dict]:
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: N802
            return None

    opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(f"{BASE}{path}", timeout=30) as response:
            return response.status, dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers)


def upload(path: str, file_path: Path, token: str):
    content = file_path.read_bytes()
    boundary = "----smoke" + uuid.uuid4().hex
    content_type = {
        ".pdf": "application/pdf",
        ".png": "image/png",
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".webp": "image/webp",
    }.get(file_path.suffix.lower(), "application/octet-stream")
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{file_path.name}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    return call("POST", path, raw=body,
                content_type=f"multipart/form-data; boundary={boundary}", token=token)


def check(name: str, condition: bool, detail: Any = "") -> None:
    global PASSED
    if condition:
        PASSED += 1
        print(f"  PASS  {name}")
    else:
        FAILED.append(name)
        print(f"  FAIL  {name}  -> {detail}")


def section(title: str) -> None:
    print(f"\n== {title} ==")


def main() -> int:
    suffix = uuid.uuid4().hex[:8]
    username = f"smoke_{suffix}"
    password = "pass1234"

    section("认证")
    status, body, _ = call("POST", "/auth/register", {"identifier": username, "password": password})
    check("register 201", status == 201, (status, body))
    token = (body or {}).get("access_token")
    check("access_token 两段", isinstance(token, str) and token.count(".") == 1, body)
    check("token_type=bearer", (body or {}).get("token_type") == "bearer", body)
    check("expires_in_seconds", (body or {}).get("expires_in_seconds") == 2592000, body)

    status, body, headers = call("GET", "/auth/me")
    check("未登录 401", status == 401, (status, body))
    check("401 文案", (body or {}).get("detail") == "请先登录后再操作。", body)
    check("401 带 WWW-Authenticate", "Bearer" in (headers.get("WWW-Authenticate") or ""), headers)

    status, body, _ = call("GET", "/auth/me", token=token)
    check("me 200", status == 200, (status, body))
    check("me snake_case", "created_at" in (body or {}), body)

    status, body, _ = call("POST", "/auth/login", {"identifier": username, "password": "wrongpass1"})
    check("密码错 401", status == 401, (status, body))
    check("登录失败文案",
          (body or {}).get("detail") == "用户名/邮箱/手机号或密码不正确。", body)

    status, body, _ = call("POST", "/auth/login", {"identifier": username, "password": password})
    check("登录 200", status == 200, (status, body))
    token = (body or {}).get("access_token", token)

    status, body, _ = call("POST", "/auth/register", {"identifier": username, "password": password})
    check("重复注册 409", status == 409, (status, body))
    check("重复注册文案",
          (body or {}).get("detail") == "这个用户名已经被占用了，换一个吧。", body)

    status, body, _ = call("POST", "/auth/register", {"identifier": f"u{suffix}", "password": "short"})
    check("短密码 422", status == 422, (status, body))
    check("短密码文案", (body or {}).get("detail") == "密码至少 8 位。", body)

    status, body, _ = call("POST", "/auth/login", {"identifier": "x", "password": password})
    check("标识过短 422", status == 422, (status, body))

    email = f"smoke_{suffix}@example.com"
    status, body, _ = call("PATCH", "/auth/me", {"email": email}, token=token)
    check("PATCH me 200", status == 200, (status, body))
    check("PATCH me 写 email", (body or {}).get("email") == email, body)
    status, body, _ = call("PATCH", "/auth/me", {"email": "not-an-email"}, token=token)
    check("非法邮箱 422", status == 422, (status, body))
    status, body, _ = call("PATCH", "/auth/me", {"email": "13800138000"}, token=token)
    check("手机号塞邮箱 422", status == 422, (status, body))
    check("手机号塞邮箱文案", (body or {}).get("detail") == "邮箱格式不正确。", body)

    section("旅行")
    status, body, _ = call("POST", "/trips",
                           {"title": "南京四日", "destination": "南京",
                            "start_date": "2026-11-01", "end_date": "2026-11-04",
                            "budget": 8000, "adult_male_count": 1, "adult_female_count": 1},
                           token=token)
    check("建行程 201", status == 201, (status, body))
    trip = body or {}
    trip_id = trip.get("id")
    check("created_at 存在", trip.get("created_at") is not None, body)
    check("start_date 格式", trip.get("start_date") == "2026-11-01", body)
    check("未传人数落 0", trip.get("child_male_count") == 0, body)

    status, body, _ = call("POST", "/trips",
                           {"title": "倒置", "destination": "南京",
                            "start_date": "2026-11-05", "end_date": "2026-11-01"}, token=token)
    check("日期倒置 422", status == 422, (status, body))
    check("日期倒置文案", (body or {}).get("detail") == "结束日期不能早于出发日期", body)

    status, body, _ = call("GET", "/trips", token=token)
    check("行程列表 200", status == 200, (status, body))
    check("列表含本人行程", any(item.get("id") == trip_id for item in (body or [])), body)

    status, body, _ = call("GET", "/trips/99999999", token=token)
    check("不存在 404", status == 404, (status, body))
    check("不存在文案", (body or {}).get("detail") == "旅行项目不存在", body)

    section("收藏地点")
    status, body, _ = call("POST", f"/trips/{trip_id}/places",
                           {"name": "夫子庙", "type": "attraction", "address": "秦淮区",
                            "latitude": 32.0207, "longitude": 118.7886,
                            "opening_hours": "09:00-17:00", "price_level": 2,
                            "note": "夜景好", "estimated_cost": 50, "quantity": 1}, token=token)
    check("建地点 201", status == 201, (status, body))
    place_id = (body or {}).get("id")
    check("type 小写", (body or {}).get("type") == "attraction", body)

    status, body, _ = call("POST", f"/trips/{trip_id}/places",
                           {"name": "坏类型", "type": "spaceship", "address": "x"}, token=token)
    check("非法 type 422", status == 422, (status, body))

    status, body, _ = call("PATCH", f"/trips/{trip_id}/places/{place_id}",
                           {"estimated_cost": 88, "quantity": 2}, token=token)
    check("PATCH 200", status == 200, (status, body))
    check("PATCH 改价", (body or {}).get("estimated_cost") == 88, body)
    check("PATCH 未传不变", (body or {}).get("name") == "夫子庙", body)
    status, body, _ = call("PATCH", f"/trips/{trip_id}/places/{place_id}", {"note": None}, token=token)
    check("PATCH null 清空", (body or {}).get("note") is None, body)

    status, body, _ = call("POST", f"/trips/{trip_id}/places",
                           {"name": "南京博物院", "type": "attraction", "address": "中山东路321号",
                            "opening_hours": "09:00-17:00", "price_level": 1}, token=token)
    place2_id = (body or {}).get("id")
    status, body, _ = call("POST", f"/trips/{trip_id}/places",
                           {"name": "金陵饭店", "type": "hotel", "address": "汉中路2号",
                            "opening_hours": "14:00-23:59", "price_level": 4}, token=token)
    check("建酒店 201", status == 201, (status, body))

    section("行程项")
    status, body, _ = call("POST", f"/trips/{trip_id}/itinerary-items",
                           {"date": "2026-11-01", "start_time": "09:30:00",
                            "end_time": "12:00:00", "place_id": place_id, "note": "上午"}, token=token)
    check("建行程项 201", status == 201, (status, body))
    item_id = (body or {}).get("id")
    check("内嵌 place", ((body or {}).get("place") or {}).get("name") == "夫子庙", body)
    check("时间带秒", (body or {}).get("start_time") == "09:30:00", body)

    status, body, _ = call("POST", f"/trips/{trip_id}/itinerary-items",
                           {"date": "2026-11-01", "start_time": "12:00:00",
                            "end_time": "10:00:00", "place_id": place_id}, token=token)
    check("时间倒置 422", status == 422, (status, body))
    check("时间倒置文案", (body or {}).get("detail") == "结束时间不能早于开始时间", body)

    status, body, _ = call("POST", f"/trips/{trip_id}/itinerary-items",
                           {"date": "2026-12-31", "start_time": "09:00:00",
                            "end_time": "10:00:00", "place_id": place_id}, token=token)
    check("日期越界 422", status == 422, (status, body))
    check("日期越界文案", (body or {}).get("detail") == "行程日期或地点无效", body)

    status, body, _ = call("POST", f"/trips/{trip_id}/itinerary-items/auto-schedule", token=token)
    check("自动排期 200", status == 200, (status, body))
    body = body or {}
    check("已排进 already_scheduled", "夫子庙" in (body.get("already_scheduled") or []), body)
    check("剩余地点给建议", len(body.get("proposed") or []) == 2, body)

    status, body, _ = call("DELETE", f"/trips/{trip_id}/itinerary-items/{item_id}", token=token)
    check("删行程项 204", status == 204, (status, body))
    status, body, _ = call("DELETE", f"/trips/{trip_id}/places/{place2_id}", token=token)
    check("删地点 204", status == 204, (status, body))
    status, body, _ = call("DELETE", f"/trips/{trip_id}/places", token=token)
    check("清空收藏 200", status == 200, (status, body))
    check("清空返回 removed", (body or {}).get("removed") == 2, body)

    section("文件与检索")
    assets = ROOT / "tmp"
    pdf_path = assets / "hotel_booking.pdf"
    png_path = assets / "hotel_booking.png"
    if pdf_path.exists():
        status, body, _ = upload(f"/trips/{trip_id}/files", pdf_path, token)
        check("上传 PDF 201", status == 201, (status, body))
        pdf_id = (body or {}).get("id")
        check("parse_status=pending", (body or {}).get("parse_status") == "pending", body)

        status, body, _ = call("GET", f"/trips/{trip_id}/files/{pdf_id}/parse/result", token=token)
        check("未解析 409", status == 409, (status, body))
        check("未解析文案", (body or {}).get("detail") == "文件还没有解析完成。", body)

        status, raw, _ = call_raw("GET", f"/trips/{trip_id}/files/{pdf_id}/download", token)
        check("下载 200", status == 200, status)
        check("下载是 PDF", raw[:4] == b"%PDF", raw[:8])

        status, body, _ = call("POST", f"/trips/{trip_id}/files/{pdf_id}/parse", token=token)
        check("解析 PDF 200", status == 200, (status, body))
        parsed = (body or {}).get("parsed_result") or {}
        check("解析 completed", (body or {}).get("parse_status") == "completed", body)
        check("结构化 7 键", len(parsed.keys()) == 7, list(parsed.keys()))
        check("hotel_names 命中", "Nanjing Jinling Hotel" in (parsed.get("hotel_names") or []), parsed)
        check("dates 命中", "2026-11-01" in (parsed.get("dates") or []), parsed)
        check("times 命中", "14:00" in (parsed.get("times") or []), parsed)
        check("订单号命中", "HTL20261101001" in (parsed.get("reference_numbers") or []), parsed)

        status, body, _ = call("GET", f"/trips/{trip_id}/knowledge/status", token=token)
        check("知识库 200", status == 200, (status, body))
        check("索引有片段", ((body or {}).get("indexed_chunks") or 0) > 0, body)
        check("embedding_dim=1024", (body or {}).get("embedding_dim") == 1024, body)

        status, body, _ = call("GET", f"/trips/{trip_id}/knowledge/search?q=%E8%AE%A2%E5%8D%95%E5%8F%B7",
                               token=token)
        check("检索 ok", (body or {}).get("status") == "ok", body)
        chunks = (body or {}).get("chunks") or []
        check("命中片段", len(chunks) > 0, body)
        if chunks:
            check("带来源文件名", chunks[0].get("source_label") == "hotel_booking.pdf", chunks[0])
            check("相似度在 [-1,1]", -1 <= chunks[0].get("similarity", 99) <= 1, chunks[0])

        status, body, _ = call("GET", f"/trips/{trip_id}/knowledge/search?q=x&limit=0", token=token)
        check("limit 越界 422", status == 422, (status, body))
        status, body, _ = call("POST", f"/trips/{trip_id}/knowledge/rebuild", token=token)
        check("重建索引 200", status == 200, (status, body))

        if png_path.exists():
            status, body, _ = upload(f"/trips/{trip_id}/files", png_path, token)
            png_id = (body or {}).get("id")
            check("上传 PNG 201", status == 201, (status, body))
            status, body, _ = call("POST", f"/trips/{trip_id}/files/{png_id}/parse", token=token)
            check("解析 PNG 200", status == 200, (status, body))
            text = ((body or {}).get("parsed_text") or "").lower()
            check("图片提出文字", len(text) > 10, text[:100])
            check("图片文字含酒店名", "jinling" in text, text[:160])
            call("DELETE", f"/trips/{trip_id}/files/{png_id}", token=token)

        bad = assets / "not_allowed.txt"
        bad.write_text("hello", encoding="utf-8")
        status, body, _ = upload(f"/trips/{trip_id}/files", bad, token)
        check("上传 txt 400", status == 400, (status, body))
        check("上传 txt 文案",
              (body or {}).get("detail") == "只支持 JPG、JPEG、PNG、WEBP 和 PDF 文件。", body)

        status, body, _ = call("DELETE", f"/trips/{trip_id}/files/{pdf_id}", token=token)
        check("删文件 204", status == 204, (status, body))
    else:
        print("  跳过：先运行 tools/make_test_files.py")

    section("静态资源与图片代理")
    request = urllib.request.Request("http://127.0.0.1:8001/static/place_images/hotel.svg")
    with urllib.request.urlopen(request, timeout=10) as response:
        svg = response.read()
        check("占位图公开可访问", response.status == 200, response.status)
        check("占位图是 SVG", b"<svg" in svg[:200], svg[:40])

    status, headers = call_no_redirect(
        "/image-proxy?url=https%3A%2F%2Fevil.example.com%2Fa.jpg&category=restaurant")
    check("非白名单 307", status == 307, status)
    check("307 指向餐厅占位图",
          (headers.get("Location") or "").endswith("/static/place_images/restaurant.svg"),
          headers.get("Location"))
    status, headers = call_no_redirect("/image-proxy?url=https%3A%2F%2Fevil.example.com%2Fa.jpg")
    check("无分类回落 generic",
          (headers.get("Location") or "").endswith("/static/place_images/generic.svg"),
          headers.get("Location"))

    section("规划流水线")
    status, body, _ = call("POST", f"/trips/{trip_id}/plan-jobs",
                           {"query": "推荐南京的景点和美食", "include_web": True}, token=token)
    check("plan-jobs 202", status == 202, (status, body))
    job = body or {}
    job_id = job.get("job_id")
    check("返回 job_id", isinstance(job_id, str) and len(job_id) > 8, body)
    check("初始 queued/running", job.get("status") in ("queued", "running"), body)

    for _ in range(180):
        if not job_id:
            break
        status, body, _ = call("GET", f"/trips/{trip_id}/plan-jobs/{job_id}", token=token)
        if status != 200:
            break
        job = body or {}
        if job.get("status") in ("completed", "failed"):
            break
        time.sleep(2)

    check("任务 completed", (job or {}).get("status") == "completed", (job or {}).get("error") or job)
    if (job or {}).get("status") == "completed":
        plan = job.get("result") or {}
        check("trip_id 对得上", plan.get("trip_id") == trip_id, plan.get("trip_id"))
        check("有 summary", bool(plan.get("summary")), plan.get("summary"))
        check("有 final_summary", bool(plan.get("final_summary")), plan.get("final_summary"))
        check("budget.total_estimate 是数字",
              isinstance((plan.get("budget") or {}).get("total_estimate"), int), plan.get("budget"))
        check("有 budget_plan", isinstance(plan.get("budget_plan"), dict), plan.get("budget_plan"))
        tools = [event.get("tool") for event in (plan.get("events") or [])]
        check("事件含 place_recommender", "place_recommender" in tools, tools)
        check("事件含 final_summary_generator", "final_summary_generator" in tools, tools)
        check("有 daily_plan", isinstance(plan.get("daily_plan"), list), plan.get("daily_plan"))
        check("有 weather", isinstance(plan.get("weather"), dict), plan.get("weather"))
        cards = plan.get("place_recommendations") or []
        check("price_pending=false", all(card.get("price_pending") is False for card in cards), cards[:1])
        if cards:
            check("卡片字段齐全",
                  {"name", "category", "estimated_cost", "quantity", "price_tier"} <= set(cards[0].keys()),
                  list(cards[0].keys()))

    status, body, _ = call("GET", f"/trips/{trip_id}/plan-jobs/nonexistent", token=token)
    check("未知 job 404", status == 404, (status, body))
    check("未知 job 文案", (body or {}).get("detail") == "规划任务不存在。", body)

    # 所有面向用户的错误都必须是中文：抽查几条错误响应里有没有英文句子
    for path, name in (
        (f"/trips/{trip_id}/files/99999999", "文件不存在"),
        (f"/trips/{trip_id}/adjustments/99999999", "草案不存在"),
    ):
        status, body, _ = call("GET", path, token=token)
        detail = str((body or {}).get("detail") or "")
        check(f"{name}返回中文", any("\u4e00" <= ch <= "\u9fff" for ch in detail), detail)

    section("行程调整（两阶段写入）")
    status, body, _ = call("POST", f"/trips/{trip_id}/places",
                           {"name": "先锋书店", "type": "attraction", "address": "广州路173号",
                            "price_level": 1}, token=token)
    adj_place = (body or {}).get("id")
    check("准备地点 201", status == 201, (status, body))

    draft_body = {
        "instruction": "把先锋书店排到 2026-11-02 上午",
        "include_web": False,
        "structured_only": True,
        "operations": [{
            "kind": "add_item",
            "place_id": adj_place,
            "date": "2026-11-02",
            "start_time": "09:00:00",
            "end_time": "11:00:00",
            "note": "上午逛书店",
            "sort_order": 0,
        }],
    }
    status, body, _ = call("POST", f"/trips/{trip_id}/adjustments", draft_body, token=token)
    check("草案 201", status == 201, (status, body))
    adjustment_id = (body or {}).get("id")
    check("草案 pending", (body or {}).get("status") == "pending", body)
    check("草案 1 条操作", len((body or {}).get("operations") or []) == 1, body.get("operations"))
    first = ((body or {}).get("operations") or [{}])[0]
    check("省略 null 的 budget_amount", "budget_amount" not in first, first)

    status, body, _ = call("GET", f"/trips/{trip_id}/adjustments", token=token)
    check("草案列表 200", status == 200, (status, body))
    check("列表含该草案", any(item.get("id") == adjustment_id for item in (body or [])), body)

    status, body, _ = call("POST", f"/trips/{trip_id}/adjustments/{adjustment_id}/apply", None, token=token)
    check("apply 200", status == 200, (status, body))
    check("apply status=applied", (body or {}).get("status") == "applied", body)
    check("返回 itinerary_item_ids", isinstance((body or {}).get("itinerary_item_ids"), list), body)

    status, body, _ = call("GET", f"/trips/{trip_id}/itinerary-items", token=token)
    check("行程出现新项", any(item.get("place_id") == adj_place for item in (body or [])), body)

    status, body, _ = call("POST", f"/trips/{trip_id}/adjustments/{adjustment_id}/apply", None, token=token)
    check("重复 apply 409", status == 409, (status, body))

    status, body, _ = call("POST", f"/trips/{trip_id}/adjustments", draft_body, token=token)
    reject_id = (body or {}).get("id")
    status, body, _ = call("POST", f"/trips/{trip_id}/adjustments/{reject_id}/reject", None, token=token)
    check("reject 200", status == 200, (status, body))
    check("reject 后 rejected", (body or {}).get("status") == "rejected", body)
    status, body, _ = call("POST", f"/trips/{trip_id}/adjustments/{reject_id}/apply", None, token=token)
    check("已拒绝不可 apply 409", status == 409, (status, body))

    section("越权与隔离")
    status, body, _ = call("POST", "/auth/register",
                           {"identifier": f"smoke2_{suffix}", "password": password})
    other = (body or {}).get("access_token")
    check("第二账号 201", status == 201, (status, body))
    status, body, _ = call("GET", f"/trips/{trip_id}", token=other)
    check("访问他人行程 404", status == 404, (status, body))
    status, body, _ = call("GET", f"/trips/{trip_id}/places", token=other)
    check("访问他人收藏 404", status == 404, (status, body))

    status, body, _ = call("DELETE", f"/trips/{trip_id}", token=token)
    check("删行程 204", status == 204, (status, body))

    print(f"\n结果：{PASSED} passed, {len(FAILED)} failed")
    if FAILED:
        print("失败用例：" + "、".join(FAILED))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
