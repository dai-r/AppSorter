# 出力名は module 化前と同じ（local.properties への受け渡し手順を変えないため）
output "api_invoke_url" {
  value       = module.appsorter.api_invoke_url
  description = "AppSorter から POST する URL"
}

output "cognito_identity_pool_id" {
  value       = module.appsorter.cognito_identity_pool_id
  description = "Android 側で AWSMobileClient / Cognito Identity に渡す ID"
}

output "aws_region" {
  value = module.appsorter.aws_region
}

output "lambda_function_name" {
  value = module.appsorter.lambda_function_name
}
