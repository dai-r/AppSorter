output "api_invoke_url" {
  value       = "${aws_api_gateway_stage.stage.invoke_url}/categorize"
  description = "AppSorter から POST する URL"
}

output "cognito_identity_pool_id" {
  value       = aws_cognito_identity_pool.pool.id
  description = "Android 側で AWSMobileClient / Cognito Identity に渡す ID"
}

output "aws_region" {
  value = var.aws_region
}

output "lambda_function_name" {
  value = aws_lambda_function.categorizer.function_name
}
