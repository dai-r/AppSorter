variable "project" {
  type        = string
  default     = "appsorter"
  description = "リソース名プレフィックス"
}

variable "aws_region" {
  type        = string
  default     = "ap-northeast-1"
  description = "東京リージョン前提。Bedrockモデル可用性は要確認（README参照）"
}

variable "bedrock_model_id" {
  type        = string
  default     = "jp.anthropic.claude-haiku-4-5-20251001-v1:0"
  description = <<-EOT
    Bedrock のモデルid。Converse APIで呼び出しているのでモデル横断で差し替え可能。
    事前にコンソールでモデルアクセスを申請・許可しておく。
    Haiku 4.5は東京で on-demand 不可なので、JP内クロスリージョン推論プロファイル
    `jp.anthropic.claude-haiku-4-5-20251001-v1:0` を使用（ap-northeast-1, ap-northeast-3にルーティング）。
    Global版は `global.anthropic.claude-haiku-4-5-20251001-v1:0`（US/EU等にもルーティング）。
    Nova 切替候補: `amazon.nova-micro-v1:0` / `amazon.nova-lite-v1:0`
  EOT
}

variable "stage_name" {
  type        = string
  default     = "v1"
  description = "API Gateway ステージ名"
}

variable "throttle_rate_limit" {
  type        = number
  default     = 10
  description = "API Gateway 定常リクエスト/秒（ステージ全体）"
}

variable "throttle_burst_limit" {
  type        = number
  default     = 20
  description = "API Gateway バースト上限"
}

variable "max_apps_per_request" {
  type        = number
  default     = 50
  description = "1リクエストで処理するアプリ数の上限"
}

variable "lambda_memory_mb" {
  type    = number
  default = 512
}

variable "lambda_timeout_sec" {
  type    = number
  default = 30
}

variable "lambda_reserved_concurrency" {
  type        = number
  default     = 5
  description = "コスト・暴走抑制用の同時実行上限"
}

variable "log_retention_days" {
  type    = number
  default = 14
}

variable "daily_limit_per_identity" {
  type        = number
  default     = 10
  description = "1 Cognito identity あたりの1日のAPI呼び出し上限"
}

variable "monthly_limit_per_identity" {
  type        = number
  default     = 100
  description = "1 Cognito identity あたりの1ヶ月のAPI呼び出し上限"
}

variable "manage_apigw_account" {
  type        = bool
  default     = true
  description = "API Gateway の CloudWatch ログ用アカウントロールを作るか。既に他スタックで設定済みなら false"
}

variable "budget_limit_usd" {
  type        = number
  default     = 10
  description = "月額USDのハード上限。100%到達でDynamoDBのkill switchが発火して全リクエスト503に"
}

variable "notification_email" {
  type        = string
  default     = "your-email@example.com"
  description = "Budget通知のメール送信先。実値は terraform.tfvars で上書きする（このリポジトリは公開可能性があるため個人メールをコードに残さない）"
}
