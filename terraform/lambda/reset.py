"""Monthly kill switch auto-reset.

EventBridge Scheduled Rule が月初 (UTC 00:00 on day 1) にこれを呼ぶ。
SWITCH#GLOBAL.locked を false に戻してサービスを再開する。

注: 月初リセットは「ユーザー側で原因追求済み」を前提にする。
意図しない超過が繰り返し起きる場合は、EventBridge Rule を無効化するか
budget_limit_usd を下げるか、根本原因を解消してから再有効化すること。
"""

from __future__ import annotations

import logging
import os
from datetime import datetime, timezone
from typing import Any

import boto3

logger = logging.getLogger()
logger.setLevel(os.environ.get("LOG_LEVEL", "INFO"))

TABLE = os.environ["RATE_LIMIT_TABLE"]

_ddb = boto3.client("dynamodb")


def lambda_handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat()
    _ddb.update_item(
        TableName=TABLE,
        Key={"pk": {"S": "SWITCH"}, "sk": {"S": "GLOBAL"}},
        UpdateExpression="SET locked = :f, reason = :r, updated_at = :u",
        ExpressionAttributeValues={
            ":f": {"BOOL": False},
            ":r": {"S": "monthly auto reset"},
            ":u": {"S": now},
        },
    )
    logger.info("kill switch auto-reset at %s", now)
    return {"ok": True, "reset_at": now}
