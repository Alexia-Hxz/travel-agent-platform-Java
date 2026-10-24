"""验证「Python 版生成的密码哈希，Java 版能校验通过」。

流程：用 hashlib.scrypt 按原格式生成一个哈希 → 插入 users（id=999）→ 调 /auth/login
→ 再用拿到的 token 调 /auth/me → 删除临时账号。

这证明迁移后的老账号用原密码即可登录，而不只是"数据看起来在"。

用法：python tools/verify_old_login.py
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8001/api/v1"
EMAIL = "olduser@example.com"
PASSWORD = "migrate-test-1234"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_PATH = os.path.join(ROOT, "backend", ".env")


def read_env() -> dict[str, str]:
    values: dict[str, str] = {}
    if os.path.exists(ENV_PATH):
        with open(ENV_PATH, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if "=" in line and not line.strip().startswith("#"):
                    key, _, value = line.partition("=")
                    values[key.strip()] = value.strip()
    return values


def hash_password(password: str) -> str:
    """与 Python 版 auth_service.hash_password 完全相同的格式。"""
    salt = os.urandom(16)
    derived = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=2**14, r=8, p=1, dklen=64)
    return "scrypt$16384$8$1${}${}".format(
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(derived).decode("ascii"),
    )


def mysql(sql: str) -> None:
    env = read_env()
    command = [
        "mysql",
        "--default-character-set=utf8mb4",
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


def call(method: str, path: str, body=None, token: str | None = None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"{BASE}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"null")


def main() -> int:
    stored = hash_password(PASSWORD)
    mysql(
        "INSERT INTO users (id, email, password_hash, created_at, failed_login_count)"
        f" VALUES (999, '{EMAIL}', '{stored}', UTC_TIMESTAMP(), 0)"
        " ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash);"
    )
    try:
        status, body = call("POST", "/auth/login", {"identifier": EMAIL, "password": PASSWORD})
        ok = status == 200 and isinstance(body, dict) and body.get("access_token")
        print(f"登录（Python 生成的哈希）: HTTP {status} -> {'成功' if ok else body}")
        if not ok:
            return 1

        token = body["access_token"]
        status, me = call("GET", "/auth/me", token=token)
        print(f"用 token 读 /auth/me: HTTP {status} -> {me.get('email') if isinstance(me, dict) else me}")
        if status != 200 or not isinstance(me, dict) or me.get("email") != EMAIL:
            return 1

        status, _ = call("POST", "/auth/login", {"identifier": EMAIL, "password": "wrong-password"})
        print(f"错误密码: HTTP {status}（应为 401）")
        if status != 401:
            return 1

        print("结论：迁移后的老账号可以用原密码登录。")
        return 0
    finally:
        mysql("DELETE FROM users WHERE id = 999;")


if __name__ == "__main__":
    sys.exit(main())
