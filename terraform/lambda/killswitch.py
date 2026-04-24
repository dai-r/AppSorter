"""Budget 超過時に DynamoDB の kill switch item を locked=true にする。

SNS -> Lambda (this) -> DynamoDB。
メインの categorizer Lambda が各リクエストで ConditionCheck してこれを見る。
"""

from __future__ import annotations

import json
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
    # SNS 経由。記録用に中身を残す
    try:
        msg = event["Records"][0]["Sns"]["Message"]
    except (KeyError, IndexError):
        msg = json.dumps(event)[:500]
    logger.info("budget alert received: %s", msg[:500])

    now = datetime.now(timezone.utc).isoformat()
    _ddb.update_item(
        TableName=TABLE,
        Key={"pk": {"S": "SWITCH"}, "sk": {"S": "GLOBAL"}},
        UpdateExpression="SET locked = :t, reason = :r, updated_at = :u",
        ExpressionAttributeValues={
            ":t": {"BOOL": True},
            ":r": {"S": "budget exceeded"},
            ":u": {"S": now},
        },
    )
    logger.warning("kill switch engaged at %s", now)
    return {"ok": True}
