"""Bedrock-backed app categorizer with per-identity daily/monthly rate limiting.

API Gateway -> Lambda (this) -> DynamoDB (counters) -> Bedrock InvokeModel.
"""

from __future__ import annotations

import json
import logging
import os
import random
import time
from datetime import datetime, timezone
from typing import Any

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO")
BEDROCK_MODEL_ID = os.environ["BEDROCK_MODEL_ID"]
MAX_APPS = int(os.environ.get("MAX_APPS_PER_REQUEST", "50"))
MAX_BODY_BYTES = 64 * 1024  # 64KB

RATE_LIMIT_TABLE = os.environ["RATE_LIMIT_TABLE"]
DAILY_LIMIT = int(os.environ["DAILY_LIMIT_PER_IDENTITY"])
MONTHLY_LIMIT = int(os.environ["MONTHLY_LIMIT_PER_IDENTITY"])
# 全 identity 合計の日次上限。未認証 identity は GetId で量産できるので、identity 単位の上限とは別に最終防衛線を置く
GLOBAL_DAILY_LIMIT = int(os.environ["GLOBAL_DAILY_LIMIT"])

# DynamoDB TTL margins
DAILY_TTL_SECONDS = 2 * 24 * 3600       # daily レコードは2日で消す
MONTHLY_TTL_SECONDS = 62 * 24 * 3600    # monthly レコードは62日で消す

logger = logging.getLogger()
logger.setLevel(LOG_LEVEL)

_bedrock = boto3.client(
    "bedrock-runtime",
    config=Config(retries={"max_attempts": 2, "mode": "standard"}),
)
_ddb = boto3.client("dynamodb")

CATEGORIES = [
    "Communication", "SNS", "Mail",
    "Video", "Music", "Photo", "Game",
    "Shopping", "Payment", "Points", "Banking", "Crypto",
    "Work", "News", "Navigation", "Travel", "Mobility",
    "Food", "Health", "Education", "Utility", "System",
    "Other",
]

SYSTEM_PROMPT = (
    "You categorize Android apps. For every app in the user's JSON list "
    "(each has appName and packageName), assign exactly one category from: "
    + ", ".join(CATEGORIES) + ". "
    'Respond with ONLY minified JSON matching {"results":[{"packageName":"...","category":"..."}]}. '
    "No prose, no code fences."
)


def _resp(status: int, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "statusCode": status,
        "headers": {
            "Content-Type": "application/json",
            "Cache-Control": "no-store",
            "Strict-Transport-Security": "max-age=31536000",
            "X-Content-Type-Options": "nosniff",
        },
        "body": json.dumps(payload, ensure_ascii=False),
    }


def _bad_request(msg: str) -> dict[str, Any]:
    return _resp(400, {"error": msg})


def _extract_json(text: str) -> dict[str, Any]:
    """モデル出力からJSONを抽出。コードフェンス・前後プロズに耐える。"""
    if not text:
        return {}
    s = text.strip()
    # ```json ... ``` や ``` ... ``` を剥がす
    if s.startswith("```"):
        lines = s.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        s = "\n".join(lines).strip()
    # 先に試す：純粋なJSON
    try:
        return json.loads(s)
    except json.JSONDecodeError:
        pass
    # フォールバック：最外の { ... } を抽出
    start = s.find("{")
    end = s.rfind("}")
    if start != -1 and end > start:
        candidate = s[start:end + 1]
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            pass
    return {}


def _get_identity_id(event: dict[str, Any]) -> str | None:
    ctx = event.get("requestContext", {}) or {}
    identity = ctx.get("identity", {}) or {}
    # Cognito Identity Pool unauth 経由だと cognitoIdentityId が入る
    return identity.get("cognitoIdentityId")


def _consume_quota(identity_id: str) -> tuple[bool, str]:
    """Kill switch確認 + Daily + Monthly + 全体Daily カウンタを単一トランザクションで原子的に処理。
    どれか1つでも条件違反なら全て巻き戻り False を返す。
    全体カウンタは全リクエストが同じ項目を更新するため、同時リクエスト同士の衝突（TransactionConflict）は1回だけやり直す。
    scope: "killswitch" / "daily" / "monthly" / "global" / "conflict" / "" (成功)
    """
    allowed, scope = _try_consume_quota(identity_id)
    if scope == "conflict":
        time.sleep(random.uniform(0.05, 0.2))
        allowed, scope = _try_consume_quota(identity_id)
    return allowed, scope


def _try_consume_quota(identity_id: str) -> tuple[bool, str]:
    now = datetime.now(timezone.utc)
    day_key = now.strftime("DAY#%Y-%m-%d")
    month_key = now.strftime("MON#%Y-%m")
    ttl_day = int(time.time()) + DAILY_TTL_SECONDS
    ttl_month = int(time.time()) + MONTHLY_TTL_SECONDS

    try:
        _ddb.transact_write_items(
            TransactItems=[
                # [0] Budget kill switch が立っていないか確認
                {
                    "ConditionCheck": {
                        "TableName": RATE_LIMIT_TABLE,
                        "Key": {
                            "pk": {"S": "SWITCH"},
                            "sk": {"S": "GLOBAL"},
                        },
                        "ConditionExpression": "attribute_not_exists(locked) OR locked = :false",
                        "ExpressionAttributeValues": {
                            ":false": {"BOOL": False},
                        },
                    }
                },
                # [1] 日次カウンタ
                {
                    "Update": {
                        "TableName": RATE_LIMIT_TABLE,
                        "Key": {
                            "pk": {"S": f"ID#{identity_id}"},
                            "sk": {"S": day_key},
                        },
                        "UpdateExpression": "ADD #c :one SET #t = if_not_exists(#t, :ttl)",
                        "ConditionExpression": "attribute_not_exists(#c) OR #c < :limit",
                        "ExpressionAttributeNames": {"#c": "count", "#t": "ttl"},
                        "ExpressionAttributeValues": {
                            ":one": {"N": "1"},
                            ":limit": {"N": str(DAILY_LIMIT)},
                            ":ttl": {"N": str(ttl_day)},
                        },
                    }
                },
                # [2] 月次カウンタ
                {
                    "Update": {
                        "TableName": RATE_LIMIT_TABLE,
                        "Key": {
                            "pk": {"S": f"ID#{identity_id}"},
                            "sk": {"S": month_key},
                        },
                        "UpdateExpression": "ADD #c :one SET #t = if_not_exists(#t, :ttl)",
                        "ConditionExpression": "attribute_not_exists(#c) OR #c < :limit",
                        "ExpressionAttributeNames": {"#c": "count", "#t": "ttl"},
                        "ExpressionAttributeValues": {
                            ":one": {"N": "1"},
                            ":limit": {"N": str(MONTHLY_LIMIT)},
                            ":ttl": {"N": str(ttl_month)},
                        },
                    }
                },
                # [3] 全体の日次カウンタ（identity をまたいだ合計）
                {
                    "Update": {
                        "TableName": RATE_LIMIT_TABLE,
                        "Key": {
                            "pk": {"S": "GLOBAL"},
                            "sk": {"S": day_key},
                        },
                        "UpdateExpression": "ADD #c :one SET #t = if_not_exists(#t, :ttl)",
                        "ConditionExpression": "attribute_not_exists(#c) OR #c < :limit",
                        "ExpressionAttributeNames": {"#c": "count", "#t": "ttl"},
                        "ExpressionAttributeValues": {
                            ":one": {"N": "1"},
                            ":limit": {"N": str(GLOBAL_DAILY_LIMIT)},
                            ":ttl": {"N": str(ttl_day)},
                        },
                    }
                },
            ]
        )
        return True, ""
    except ClientError as e:
        code = e.response.get("Error", {}).get("Code", "")
        if code == "TransactionCanceledException":
            # TransactItemsのindexと対応するCancellationReasons
            reasons = e.response.get("CancellationReasons", []) or []
            codes = [r.get("Code") for r in reasons]
            if len(codes) >= 1 and codes[0] == "ConditionalCheckFailed":
                return False, "killswitch"
            if len(codes) >= 2 and codes[1] == "ConditionalCheckFailed":
                return False, "daily"
            if len(codes) >= 3 and codes[2] == "ConditionalCheckFailed":
                return False, "monthly"
            if len(codes) >= 4 and codes[3] == "ConditionalCheckFailed":
                return False, "global"
            if "TransactionConflict" in codes:
                return False, "conflict"
            return False, "unknown"
        logger.exception("ddb transact_write_items failed")
        raise


def lambda_handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    raw_body = event.get("body") or ""
    if isinstance(raw_body, bytes):
        raw_body = raw_body.decode("utf-8", errors="replace")
    if len(raw_body.encode("utf-8")) > MAX_BODY_BYTES:
        return _bad_request("request body too large")

    try:
        body = json.loads(raw_body or "{}")
    except json.JSONDecodeError:
        return _bad_request("invalid json")

    apps = body.get("apps")
    if not isinstance(apps, list) or not apps:
        return _bad_request("apps must be a non-empty array")
    if len(apps) > MAX_APPS:
        return _bad_request(f"max {MAX_APPS} apps per request")

    sanitized: list[dict[str, str]] = []
    for a in apps:
        if not isinstance(a, dict):
            return _bad_request("each app must be an object")
        name = str(a.get("appName", "")).strip()[:100]
        pkg = str(a.get("packageName", "")).strip()[:255]
        if not name or not pkg:
            return _bad_request("appName and packageName are required")
        sanitized.append({"appName": name, "packageName": pkg})

    identity_id = _get_identity_id(event)
    if not identity_id:
        # AWS_IAM認可を通過しているはずなのに identity が無いのは異常
        logger.warning("missing cognitoIdentityId in requestContext")
        return _resp(401, {"error": "identity required"})

    allowed, scope = _consume_quota(identity_id)
    if not allowed:
        if scope == "killswitch":
            return _resp(503, {
                "error": "service temporarily disabled",
                "reason": "budget limit reached",
            })
        if scope in ("conflict", "unknown"):
            # 衝突が解けない・DynamoDB 側のスロットリング等。レート制限ではないので 429 にしない
            return _resp(503, {"error": "busy, retry later", "reason": scope})
        return _resp(429, {
            "error": "rate limit exceeded",
            "scope": scope,
            "limits": {
                "daily": DAILY_LIMIT,
                "monthly": MONTHLY_LIMIT,
                "global_daily": GLOBAL_DAILY_LIMIT,
            },
        })

    user_payload = json.dumps({"apps": sanitized}, ensure_ascii=False)

    try:
        response = _bedrock.converse(
            modelId=BEDROCK_MODEL_ID,
            system=[{"text": SYSTEM_PROMPT}],
            messages=[{"role": "user", "content": [{"text": user_payload}]}],
            inferenceConfig={
                "maxTokens": 2000,
                "temperature": 0,
            },
        )
    except Exception:
        logger.exception("bedrock converse failed")
        return _resp(502, {"error": "bedrock invocation failed"})

    try:
        output_msg = response["output"]["message"]
        text_blocks = output_msg.get("content", [])
        text = "".join(b["text"] for b in text_blocks if isinstance(b, dict) and "text" in b)
        parsed = _extract_json(text)
        raw_results = parsed.get("results", []) if isinstance(parsed, dict) else []
    except (json.JSONDecodeError, KeyError, TypeError):
        # サンプリング用にテキストの先頭300文字のみログ（PIIリスク低）
        sample = ""
        try:
            sample = text[:300] if "text" in dir() or "text" in locals() else ""
        except Exception:
            pass
        logger.warning("model output could not be parsed; falling back to Other. sample=%r", sample)
        raw_results = []

    by_pkg: dict[str, str] = {}
    for r in raw_results:
        if not isinstance(r, dict):
            continue
        pkg = r.get("packageName")
        cat = r.get("category")
        if isinstance(pkg, str) and isinstance(cat, str):
            by_pkg[pkg] = cat

    enriched = []
    for a in sanitized:
        cat = by_pkg.get(a["packageName"], "Other")
        if cat not in CATEGORIES:
            cat = "Other"
        enriched.append({
            "appName": a["appName"],
            "packageName": a["packageName"],
            "category": cat,
            "source": "bedrock",
        })

    return _resp(200, {"results": enriched})
