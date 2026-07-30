#!/usr/bin/env python3
"""產生 hao-for-you 跨服務 HMAC 驗證（見 docs/changes/2026-07-29-cross-service-hmac-auth.md）
用的 X-Timestamp / X-Signature header，方便本機手動測試受保護端點。

只印出 header（自己貼進 curl / Swagger UI）：
    python3 scripts/sign_internal_request.py POST /v1/chat \\
        --body '{"user_id":"test","platform":"line","message":"hi"}'

印出 header 並直接用 curl 打過去：
    python3 scripts/sign_internal_request.py POST /v1/chat \\
        --body '{"user_id":"test","platform":"line","message":"hi"}' \\
        --run http://localhost:8080

GET 請求（無 body），並在 URL 帶 query string（query 不參與簽章，純粹組 URL 用）：
    python3 scripts/sign_internal_request.py GET /google/auth \\
        --query line_user_id=test123 --run http://localhost:8008

測試時間戳過期（時間窗預設 300 秒，故意送舊的驗證會被擋 401）：
    python3 scripts/sign_internal_request.py GET /google/auth \\
        --query line_user_id=test123 --timestamp $(($(date +%s) - 400)) \\
        --run http://localhost:8008
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_CANDIDATES = [
    REPO_ROOT / "hao-for-you-service" / ".env",
    REPO_ROOT / "hao-for-you-gateway" / ".env",
]


def load_secret(explicit: str | None) -> str:
    if explicit:
        return explicit
    env_secret = os.environ.get("INTERNAL_API_SECRET")
    if env_secret:
        return env_secret
    for path in ENV_CANDIDATES:
        if not path.exists():
            continue
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            if key.strip() == "INTERNAL_API_SECRET" and value.strip():
                return value.strip()
    sys.exit(
        "找不到 INTERNAL_API_SECRET。用 --secret 指定，或確認下列檔案其中之一有設值：\n"
        + "\n".join(f"  {p}" for p in ENV_CANDIDATES)
    )


def build_signing_string(timestamp: str, method: str, path: str, body: str) -> str:
    """必須與 InternalApiSigner.buildSigningString / internal_auth.build_signing_string 完全一致。"""
    return f"{timestamp}\n{method.upper()}\n{path}\n{body}"


def sign(secret: str, timestamp: str, method: str, path: str, body: str) -> str:
    signing_string = build_signing_string(timestamp, method, path, body)
    return hmac.new(
        secret.encode("utf-8"), signing_string.encode("utf-8"), hashlib.sha256
    ).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("method", help="HTTP method，例如 GET / POST")
    parser.add_argument("path", help="Request path，不含 query string，例如 /v1/chat")
    parser.add_argument(
        "--body", default="",
        help="POST body 原始字串，必須跟實際送出的 body 一字不差；GET 留空",
    )
    parser.add_argument("--body-file", help="從檔案讀 body（跟 --body 二選一）")
    parser.add_argument(
        "--query",
        help="Query string，不參與簽章，只用來組 --run 時打的完整 URL，例如 line_user_id=test123",
    )
    parser.add_argument(
        "--secret",
        help="覆寫共享密鑰（預設依序找 hao-for-you-service/.env、hao-for-you-gateway/.env 的 INTERNAL_API_SECRET）",
    )
    parser.add_argument(
        "--timestamp", help="覆寫時間戳（unix 秒，預設現在時間；測過期時間窗用）"
    )
    parser.add_argument(
        "--run", metavar="BASE_URL",
        help="不只印 header，直接用 curl 打這個 base URL，例如 http://localhost:8080",
    )
    args = parser.parse_args()

    if args.body and args.body_file:
        parser.error("--body 與 --body-file 只能擇一")
    body = Path(args.body_file).read_text() if args.body_file else args.body

    secret = load_secret(args.secret)
    timestamp = args.timestamp or str(int(time.time()))
    signature = sign(secret, timestamp, args.method, args.path, body)

    print(f"X-Timestamp: {timestamp}")
    print(f"X-Signature: {signature}")

    if args.run:
        url = args.run.rstrip("/") + args.path
        if args.query:
            url += f"?{args.query}"
        cmd = [
            "curl", "-i", "-s",
            "-X", args.method.upper(),
            url,
            "-H", f"X-Timestamp: {timestamp}",
            "-H", f"X-Signature: {signature}",
        ]
        if body:
            cmd += ["-H", "Content-Type: application/json", "-d", body]
        print(f"\n$ {' '.join(cmd)}\n")
        subprocess.run(cmd)


if __name__ == "__main__":
    main()
