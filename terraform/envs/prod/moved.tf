# module 化（terraform/ 直下 → modules/appsorter）に伴う state アドレスの付け替え。
# 適用済みの環境では不要になるが、旧 state から移行する環境のために残しておく

moved {
  from = aws_api_gateway_account.this
  to   = module.appsorter.aws_api_gateway_account.this
}

moved {
  from = aws_api_gateway_deployment.dep
  to   = module.appsorter.aws_api_gateway_deployment.dep
}

moved {
  from = aws_api_gateway_integration.lambda
  to   = module.appsorter.aws_api_gateway_integration.lambda
}

moved {
  from = aws_api_gateway_method.post_categorize
  to   = module.appsorter.aws_api_gateway_method.post_categorize
}

moved {
  from = aws_api_gateway_method_settings.throttle
  to   = module.appsorter.aws_api_gateway_method_settings.throttle
}

moved {
  from = aws_api_gateway_model.categorize_request
  to   = module.appsorter.aws_api_gateway_model.categorize_request
}

moved {
  from = aws_api_gateway_request_validator.body
  to   = module.appsorter.aws_api_gateway_request_validator.body
}

moved {
  from = aws_api_gateway_resource.categorize
  to   = module.appsorter.aws_api_gateway_resource.categorize
}

moved {
  from = aws_api_gateway_rest_api.api
  to   = module.appsorter.aws_api_gateway_rest_api.api
}

moved {
  from = aws_api_gateway_stage.stage
  to   = module.appsorter.aws_api_gateway_stage.stage
}

moved {
  from = aws_budgets_budget.monthly
  to   = module.appsorter.aws_budgets_budget.monthly
}

moved {
  from = aws_cloudwatch_event_rule.monthly_reset
  to   = module.appsorter.aws_cloudwatch_event_rule.monthly_reset
}

moved {
  from = aws_cloudwatch_event_target.monthly_reset
  to   = module.appsorter.aws_cloudwatch_event_target.monthly_reset
}

moved {
  from = aws_cloudwatch_log_group.api_access
  to   = module.appsorter.aws_cloudwatch_log_group.api_access
}

moved {
  from = aws_cloudwatch_log_group.killswitch
  to   = module.appsorter.aws_cloudwatch_log_group.killswitch
}

moved {
  from = aws_cloudwatch_log_group.lambda
  to   = module.appsorter.aws_cloudwatch_log_group.lambda
}

moved {
  from = aws_cloudwatch_log_group.reset
  to   = module.appsorter.aws_cloudwatch_log_group.reset
}

moved {
  from = aws_cognito_identity_pool.pool
  to   = module.appsorter.aws_cognito_identity_pool.pool
}

moved {
  from = aws_cognito_identity_pool_roles_attachment.roles
  to   = module.appsorter.aws_cognito_identity_pool_roles_attachment.roles
}

moved {
  from = aws_dynamodb_table.rate_limit
  to   = module.appsorter.aws_dynamodb_table.rate_limit
}

moved {
  from = aws_iam_role.apigw_cloudwatch
  to   = module.appsorter.aws_iam_role.apigw_cloudwatch
}

moved {
  from = aws_iam_role.cognito_unauth
  to   = module.appsorter.aws_iam_role.cognito_unauth
}

moved {
  from = aws_iam_role.killswitch_exec
  to   = module.appsorter.aws_iam_role.killswitch_exec
}

moved {
  from = aws_iam_role.lambda_exec
  to   = module.appsorter.aws_iam_role.lambda_exec
}

moved {
  from = aws_iam_role.reset_exec
  to   = module.appsorter.aws_iam_role.reset_exec
}

moved {
  from = aws_iam_role_policy.cognito_unauth_invoke
  to   = module.appsorter.aws_iam_role_policy.cognito_unauth_invoke
}

moved {
  from = aws_iam_role_policy.killswitch_ddb
  to   = module.appsorter.aws_iam_role_policy.killswitch_ddb
}

moved {
  from = aws_iam_role_policy.killswitch_logs
  to   = module.appsorter.aws_iam_role_policy.killswitch_logs
}

moved {
  from = aws_iam_role_policy.lambda_bedrock
  to   = module.appsorter.aws_iam_role_policy.lambda_bedrock
}

moved {
  from = aws_iam_role_policy.lambda_ddb
  to   = module.appsorter.aws_iam_role_policy.lambda_ddb
}

moved {
  from = aws_iam_role_policy.lambda_logs
  to   = module.appsorter.aws_iam_role_policy.lambda_logs
}

moved {
  from = aws_iam_role_policy.reset_ddb
  to   = module.appsorter.aws_iam_role_policy.reset_ddb
}

moved {
  from = aws_iam_role_policy.reset_logs
  to   = module.appsorter.aws_iam_role_policy.reset_logs
}

moved {
  from = aws_iam_role_policy_attachment.apigw_cloudwatch
  to   = module.appsorter.aws_iam_role_policy_attachment.apigw_cloudwatch
}

moved {
  from = aws_lambda_function.categorizer
  to   = module.appsorter.aws_lambda_function.categorizer
}

moved {
  from = aws_lambda_function.killswitch
  to   = module.appsorter.aws_lambda_function.killswitch
}

moved {
  from = aws_lambda_function.reset
  to   = module.appsorter.aws_lambda_function.reset
}

moved {
  from = aws_lambda_permission.apigw
  to   = module.appsorter.aws_lambda_permission.apigw
}

moved {
  from = aws_lambda_permission.killswitch_sns
  to   = module.appsorter.aws_lambda_permission.killswitch_sns
}

moved {
  from = aws_lambda_permission.reset_events
  to   = module.appsorter.aws_lambda_permission.reset_events
}

moved {
  from = aws_sns_topic.budget_alert
  to   = module.appsorter.aws_sns_topic.budget_alert
}

moved {
  from = aws_sns_topic_policy.budget_alert
  to   = module.appsorter.aws_sns_topic_policy.budget_alert
}

moved {
  from = aws_sns_topic_subscription.killswitch
  to   = module.appsorter.aws_sns_topic_subscription.killswitch
}
