# AppSorter 本番環境。リソース定義は modules/appsorter、ここは環境ごとの値を渡すだけ
module "appsorter" {
  source = "../../modules/appsorter"

  project                     = var.project
  aws_region                  = var.aws_region
  bedrock_model_id            = var.bedrock_model_id
  stage_name                  = var.stage_name
  throttle_rate_limit         = var.throttle_rate_limit
  throttle_burst_limit        = var.throttle_burst_limit
  max_apps_per_request        = var.max_apps_per_request
  lambda_memory_mb            = var.lambda_memory_mb
  lambda_timeout_sec          = var.lambda_timeout_sec
  lambda_reserved_concurrency = var.lambda_reserved_concurrency
  log_retention_days          = var.log_retention_days
  daily_limit_per_identity    = var.daily_limit_per_identity
  monthly_limit_per_identity  = var.monthly_limit_per_identity
  global_daily_limit          = var.global_daily_limit
  manage_apigw_account        = var.manage_apigw_account
  budget_limit_usd            = var.budget_limit_usd
  notification_email          = var.notification_email
}
