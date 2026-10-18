# AppSorter Bedrock Backend (Terraform)

AppSorter の `CategorizerBackend.CLAUDE_API`（または `LOCAL_LLM` をリモートに切り替える想定）で叩く AWS バックエンドを構築する。

## 構成

```
Android (AppSorter)
   └─ Cognito Identity Pool (unauth) ── 短期 AWS 資格情報
         └─ API Gateway REST /v1/categorize   [AWS_IAM + SigV4 + Stage throttle]
              └─ Lambda (python3.12 / arm64)
                    ├─ DynamoDB (identity別 日次/月次カウンタ + Budget kill switch)
                    └─ Bedrock Converse API (Claude Haiku 4.5 デフォルト / Nova 等切替可)

AWS Budgets (月$10上限)
   └─ 100%到達 → SNS → killswitch Lambda → DynamoDB に locked=true
                                              ↑メインLambdaが全リクエストで確認

EventBridge (月初 UTC 00:00)
   └─ reset Lambda → DynamoDB に locked=false → サービス再開
```

リージョンは **東京 (ap-northeast-1)** 前提。WAFv2 は少人数利用ではコスト過剰なため外してある（ユーザーが増えたら戻す想定）。

### ディレクトリ構成

```
terraform/
  modules/appsorter/   … リソース定義（API Gateway / Lambda / Cognito / DynamoDB / Budget）と Lambda ソース・テスト
  envs/prod/           … 本番環境の root module。backend・provider・環境ごとの値を持ち、modules/appsorter を呼ぶだけ
```

- `terraform` コマンドは **`envs/<環境>/` で実行** する。環境を増やすときは `envs/prod` を複製し、`providers.tf` の backend `key` を変える（state を環境ごとに分ける）。
- 自分の Terraform から取り込む場合は `source = "github.com/dai-r/AppSorter//terraform/modules/appsorter?ref=<tag>"` で呼べる（backend・provider は呼び出し側で設定）。
- `envs/prod/moved.tf` は旧構成（`terraform/` 直下にリソースを直書き）の state を module 配下へ付け替えるためのもの。旧構成から移行する環境では、apply 前の plan が「0 to add, 0 to destroy」で各リソースが `has moved to module.appsorter...` と表示されることを確認する。

### なぜこの形か

- **APK に長期シークレット埋め込み回避**: Cognito Identity Pool から都度短期資格情報を取得し SigV4 署名させる。キーが流出しても数時間で失効する。
- **最小権限**:
  - Lambda IAM は `bedrock:InvokeModel` をモデル ARN 1 本 / `dynamodb:UpdateItem` をレート制限テーブル 1 本に限定
  - Cognito unauth ロールは `execute-api:Invoke` を `/v1/POST/categorize` だけに限定
- **横展開防御**: API Gateway ステージで RPS/バーストを絞り、Lambda リザーブド同時実行で暴走コスト遮断。
- **identityごとのクォータ**: DynamoDB で Cognito identity 別に日次/月次の呼び出し回数をアトミック管理。上限超過は Bedrock 呼び出し前に 429 で返し課金ゼロ。
- **PII 流出対策**: API Gateway の `data_trace_enabled=false`、CloudWatch 保持 14 日、アクセスログはメタデータのみ。
- **入力検証**: API Gateway のリクエストバリデータ（JSON Schema）と Lambda 側の二重チェック。

## 前提

1. Bedrock のモデルアクセスを AWS コンソール「Bedrock > Model access」で **ap-northeast-1 (東京)** で有効化しておく。
   - デフォルトは **Claude Haiku 4.5** を **JP内クロスリージョン推論プロファイル** 経由で使用：`jp.anthropic.claude-haiku-4-5-20251001-v1:0`（ap-northeast-1, ap-northeast-3 にルーティング）。
   - 東京 on-demand では `ValidationException: Invocation ... with on-demand throughput isn't supported` で失敗するので、必ずプロファイル経由で叩く必要がある。
   - グローバル版は `global.anthropic.claude-haiku-4-5-20251001-v1:0`（US/EU等にもルーティング）。レイテンシより可用性を優先したい場合に。
   - Haiku 3.5/Haiku 3 等の旧モデルは on-demand 直接指定可。`bedrock_model_id` 変数で切替。
2. `aws configure` 済みでデプロイ用 IAM が `iam:*`, `apigateway:*`, `lambda:*`, `logs:*`, `cognito-identity:*`, `bedrock:*`, `dynamodb:*` を持っている。
3. Terraform **1.10+**（S3 ネイティブロックを使うため）と Python 3.12 がローカルにある。

## デプロイ

### 初回セットアップ（必須）

```bash
cd AppSorter/terraform/envs/prod

# 1. backend.hcl: tfstate保存先S3バケット名（アカウントIDを含むためコードに残さない）
cp backend.hcl.example backend.hcl
# backend.hcl を編集して bucket を実値に置き換える

# 2. terraform.tfvars: notification_email 等の個人/環境固有値
cp terraform.tfvars.example terraform.tfvars
# terraform.tfvars を編集して notification_email を実値に置き換える
```

両ファイルとも `.gitignore` で除外されており、個人/アカウント情報が Git に入らない設計。

> **注意: `manage_apigw_account`**
> API Gateway の CloudWatch ログ用ロール（`aws_api_gateway_account`）は **AWSアカウント×リージョンに1つだけ** の共通設定。
> 既定の `true` だと AppSorter がこのロールを作って設定する。同じアカウント・リージョンで **既に他のスタックが設定済みなら `false`** にすること（`true` のままだと上書きし合い、apply のたびに切り替わる）。
> 設定済みかどうかは `aws apigateway get-account --query cloudwatchRoleArn` で確認できる（`None` なら未設定なので `true` のままでよい）。

### apply

```bash
terraform init -backend-config=backend.hcl   # 初回のみ。partial backend に bucket 名を流し込む
terraform plan                               # 何が変わるかドライラン
terraform apply                              # 実際にデプロイ（料金発生）
```

backend 設定を変えた時は `-reconfigure` か `-migrate-state` を `init` に付けて再実行。

出力される `api_invoke_url` と `cognito_identity_pool_id`, `aws_region` を AppSorter 側のビルド設定（`local.properties` 経由 → `BuildConfig`）に渡す。

## Android 側の繋ぎ方（実装メモ）

実装済み内容（`AppSorter/app/src/main/java/dev/dair/appsorter/categorizer/` 配下）：

1. **依存関係**：`aws.sdk.kotlin:cognitoidentity`（資格情報取得のみ）、`okhttp`、`kotlinx-serialization-json`。署名は自前。
2. **資格情報**：`CognitoIdentityClient.getId` → `getCredentialsForIdentity` で短期 AWS 資格情報を取得、メモリにキャッシュ（1時間 TTL）。
3. **署名**：`SigV4Signer.kt`（自前 HMAC-SHA256 実装、~120行）で SigV4 署名。
   - smithy-kotlin の `aws-signing-default` + OkHttp の組み合わせは Host/Content-Length 周りで署名不一致（403）が出るため非採用。
   - 署名対象ヘッダは `content-type`, `host`, `x-amz-date`, `x-amz-content-sha256`, `x-amz-security-token` の最小構成。
4. **送信**：OkHttp で POST。`local.properties` の `APPSORTER_API_URL` / `APPSORTER_COGNITO_IDENTITY_POOL_ID` / `APPSORTER_AWS_REGION` を `BuildConfig` 経由で参照。
5. **レスポンス処理**：200 → JSON パース、429 → `RateLimitException`、503 → `KillSwitchException`。`MainActivity` 側で例外時は `RuleBasedAppCategorizer`（オフライン）にフォールバックして UI を止めない。
6. **JSON パース**：Lambda 側で fence 剥がし関数（`_extract_json`）を通している。Haiku 系は temperature=0 でも ` ```json ``` ` フェンスを付けてくることがあるため。

## 破棄

```bash
cd AppSorter/terraform/envs/prod
terraform destroy
```

WAF はアカウント数に応じた課金があるため、検証後は破棄推奨。

## Git に入れないもの

`.gitignore` で tfstate、tfvars、.terraform/ を除外済み。tfstate は本番では S3 バックエンド（`envs/prod/providers.tf` のコメント参照）に移すこと。

## レート制限の挙動

- DynamoDB テーブル `{project}-rate-limit` に Cognito identity ごとのカウンタ + グローバル kill switch を保持
- 1リクエストごとに `TransactWriteItems` で以下を原子的に評価:
  1. **Kill switch 未発火** (`SWITCH#GLOBAL`.locked が false/未設定) ← ConditionCheck
  2. 日次カウンタ +1 (上限未達なら)
  3. 月次カウンタ +1 (上限未達なら)
- どれか1条件でも失敗するとトランザクション全体が巻き戻る = **Bedrock 呼び出し前に判定するのでコスト発生ゼロ**
- レスポンス:
  - killswitch発火中 → `503 {"error":"service temporarily disabled","reason":"budget limit reached"}`
  - 日次/月次超過 → `429 {"error":"rate limit exceeded","scope":"daily"|"monthly"}`
- TTL で日次レコードは2日後、月次レコードは62日後に自動削除（switch item は TTL 無しで残す）

デフォルト上限（`terraform.tfvars` で変更可）:
- 日次: 10回 / identity
- 月次: 100回 / identity
- 全体: 月額 $10（これを超えると自動で kill switch）

## Budget kill switch

**全部自動で止まる仕組み:**

```
AWS Budgets (月$10)
  ├─ 50% 予測到達 → メール通知のみ
  ├─ 80% 実績到達 → メール通知のみ
  └─ 100% 実績到達 → メール + SNS → killswitch Lambda
                                      └→ DynamoDB の SWITCH#GLOBAL.locked = true

以降、すべてのリクエストが 503 で即時拒否（Bedrockコストゼロ）
```

### ロック解除

**月初 (UTC 00:00, JSTでは毎月1日 09:00) に EventBridge が reset Lambda を自動起動して DynamoDB フラグを戻す。**

ただし「Budget 超過が続いている状態で自動リセット」すると再発するので、意図しない超過が起きたら:

1. 原因調査（CloudWatch Metrics で Bedrock 呼び出し回数スパイクの犯人特定）
2. 必要なら EventBridge Rule を一時無効化して自動リセットを止める
   ```bash
   aws events disable-rule --region ap-northeast-1 --name appsorter-monthly-reset
   ```
3. `budget_limit_usd` を下げる or identity 別上限を絞る
4. 対応完了後 Rule を再有効化

#### 手動でいま解除したい場合

```bash
aws dynamodb update-item \
  --region ap-northeast-1 \
  --table-name appsorter-rate-limit \
  --key '{"pk":{"S":"SWITCH"},"sk":{"S":"GLOBAL"}}' \
  --update-expression "SET locked = :f" \
  --expression-attribute-values '{":f":{"BOOL":false}}'
```

#### いま強制ロックしたい場合

```bash
aws lambda invoke \
  --region ap-northeast-1 \
  --function-name appsorter-killswitch \
  --payload '{"Records":[{"Sns":{"Message":"manual lock"}}]}' \
  /tmp/out.json
```

## コスト注意

- Bedrock Claude Haiku 4.5: 入力 $1.00 / 出力 $5.00 per 1M tokens（2026-04 時点の目安、リージョンにより変動）
- API Gateway REST: $3.50/100万リクエスト
- DynamoDB オンデマンド: 書き込み $1.25/100万WRU、1リクエストで2WRU消費（Transact × 2アイテム）
- 少人数利用想定なら月数十円〜数百円程度で済む（WAF 無しの前提）
- CLAUDE.md の「料金が発生する操作は勝手にやらないで」方針に従い、`terraform apply` はユーザー自身が実行する前提。

## モデル切替 (Nova 等)

Lambda は **Bedrock Converse API** で呼び出しているので、`bedrock_model_id` 変数を差し替えるだけで別モデルに切替可能:

| 用途 | モデルID |
|---|---|
| デフォルト (高精度・低コスト) | `anthropic.claude-haiku-4-5-20251001-v1:0` |
| 東京未対応時 | `apac.anthropic.claude-haiku-4-5-20251001-v1:0` (APAC cross-region) |
| AWS Nova に寄せる (最廉価) | `amazon.nova-lite-v1:0` |
| AWS Nova で精度重視 | `amazon.nova-pro-v1:0` |
| 精度不足時の escalation | `anthropic.claude-sonnet-4-6-20250929-v1:0` |

切替後にモデル access を AWS コンソールで許可し、`terraform apply` で反映する。プロンプト・パース処理はそのまま動く (Converse API はモデル横断の統一インタフェース)。

## WAFを後から足すとき

ユーザーが増えて公開配布するタイミングで `waf.tf` を追加し、REST API ステージに `aws_wafv2_web_acl_association` で結びつける。Conversation の履歴に元の `waf.tf` が残っているので、必要なら復元可能。
