"""handler._consume_quota のテスト。

lambda/ は丸ごと zip されるので、テストはその外に置く（__pycache__ を zip に入れないため）。
実行: cd terraform && uvx --with boto3 pytest -p no:cacheprovider tests
"""
import json
import os
import sys
from pathlib import Path
from unittest import mock

import pytest
from botocore.exceptions import ClientError

# handler は import 時に環境変数と boto3 クライアントを使うので、先に設定する
os.environ.setdefault("AWS_DEFAULT_REGION", "ap-northeast-1")
os.environ.setdefault("BEDROCK_MODEL_ID", "test-model")
os.environ.setdefault("RATE_LIMIT_TABLE", "test-table")
os.environ.setdefault("DAILY_LIMIT_PER_IDENTITY", "10")
os.environ.setdefault("MONTHLY_LIMIT_PER_IDENTITY", "100")
os.environ.setdefault("GLOBAL_DAILY_LIMIT", "30")
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "lambda"))

import handler  # noqa: E402


def _canceled(*codes: str) -> ClientError:
    return ClientError(
        {
            "Error": {"Code": "TransactionCanceledException", "Message": "canceled"},
            "CancellationReasons": [{"Code": c} for c in codes],
        },
        "TransactWriteItems",
    )


@pytest.fixture
def ddb():
    with mock.patch.object(handler, "_ddb") as m, mock.patch.object(handler.time, "sleep"):
        yield m


def test_成功時は全体の日次カウンタも同じトランザクションで加算する(ddb):
    assert handler._consume_quota("ap-northeast-1:abc") == (True, "")

    items = ddb.transact_write_items.call_args.kwargs["TransactItems"]
    assert len(items) == 4
    global_item = items[3]["Update"]
    assert global_item["Key"]["pk"] == {"S": "GLOBAL"}
    assert global_item["Key"]["sk"]["S"].startswith("DAY#")
    assert global_item["ExpressionAttributeValues"][":limit"] == {"N": "30"}
    # identity 単位のカウンタは従来どおり
    assert items[1]["Update"]["Key"]["pk"] == {"S": "ID#ap-northeast-1:abc"}


@pytest.mark.parametrize(
    "codes, scope",
    [
        (("ConditionalCheckFailed", "None", "None", "None"), "killswitch"),
        (("None", "ConditionalCheckFailed", "None", "None"), "daily"),
        (("None", "None", "ConditionalCheckFailed", "None"), "monthly"),
        (("None", "None", "None", "ConditionalCheckFailed"), "global"),
        (("None", "None", "None", "None"), "unknown"),
        # 条件違反と衝突が混在したら条件違反を優先（やり直さない）
        (("None", "ConditionalCheckFailed", "None", "TransactionConflict"), "daily"),
        (("None", "None", "None", "ThrottlingError"), "unknown"),
    ],
)
def test_どの条件で弾かれたかをscopeで返す(ddb, codes, scope):
    ddb.transact_write_items.side_effect = _canceled(*codes)
    assert handler._consume_quota("id") == (False, scope)
    assert ddb.transact_write_items.call_count == 1


def test_衝突は1回だけやり直して成功する(ddb):
    ddb.transact_write_items.side_effect = [_canceled("None", "None", "None", "TransactionConflict"), None]
    assert handler._consume_quota("id") == (True, "")
    assert ddb.transact_write_items.call_count == 2


def test_やり直しても衝突したらconflictを返す(ddb):
    ddb.transact_write_items.side_effect = _canceled("None", "None", "None", "TransactionConflict")
    assert handler._consume_quota("id") == (False, "conflict")
    assert ddb.transact_write_items.call_count == 2


def _event() -> dict:
    return {
        "body": json.dumps({"apps": [{"appName": "A", "packageName": "a.b"}]}),
        "requestContext": {"identity": {"cognitoIdentityId": "id"}},
    }


@pytest.mark.parametrize(
    "scope, status",
    [("global", 429), ("daily", 429), ("monthly", 429), ("killswitch", 503), ("conflict", 503), ("unknown", 503)],
)
def test_scopeごとのHTTPステータス(scope, status):
    with mock.patch.object(handler, "_consume_quota", return_value=(False, scope)):
        resp = handler.lambda_handler(_event(), None)
    assert resp["statusCode"] == status
    if status == 429:
        body = json.loads(resp["body"])
        assert body["scope"] == scope
        assert body["limits"]["global_daily"] == 30
