resource "aws_api_gateway_rest_api" "api" {
  name                         = "${local.name}-api"
  disable_execute_api_endpoint = false

  endpoint_configuration {
    types = ["REGIONAL"]
  }
}

resource "aws_api_gateway_resource" "categorize" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  parent_id   = aws_api_gateway_rest_api.api.root_resource_id
  path_part   = "categorize"
}

resource "aws_api_gateway_request_validator" "body" {
  name                        = "${local.name}-body-validator"
  rest_api_id                 = aws_api_gateway_rest_api.api.id
  validate_request_body       = true
  validate_request_parameters = false
}

resource "aws_api_gateway_model" "categorize_request" {
  rest_api_id  = aws_api_gateway_rest_api.api.id
  name         = "CategorizeRequest"
  content_type = "application/json"

  schema = jsonencode({
    "$schema" = "http://json-schema.org/draft-04/schema#"
    title     = "CategorizeRequest"
    type      = "object"
    required  = ["apps"]
    properties = {
      apps = {
        type     = "array"
        minItems = 1
        maxItems = var.max_apps_per_request
        items = {
          type     = "object"
          required = ["appName", "packageName"]
          properties = {
            appName     = { type = "string", minLength = 1, maxLength = 100 }
            packageName = { type = "string", minLength = 1, maxLength = 255 }
          }
        }
      }
    }
  })
}

resource "aws_api_gateway_method" "post_categorize" {
  rest_api_id          = aws_api_gateway_rest_api.api.id
  resource_id          = aws_api_gateway_resource.categorize.id
  http_method          = "POST"
  authorization        = "AWS_IAM"
  request_validator_id = aws_api_gateway_request_validator.body.id

  request_models = {
    "application/json" = aws_api_gateway_model.categorize_request.name
  }
}

resource "aws_api_gateway_integration" "lambda" {
  rest_api_id             = aws_api_gateway_rest_api.api.id
  resource_id             = aws_api_gateway_resource.categorize.id
  http_method             = aws_api_gateway_method.post_categorize.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.categorizer.invoke_arn
  timeout_milliseconds    = 29000
}

resource "aws_lambda_permission" "apigw" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.categorizer.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.api.execution_arn}/*/*"
}

resource "aws_cloudwatch_log_group" "api_access" {
  name              = "/aws/api-gateway/${local.name}-access"
  retention_in_days = var.log_retention_days
}

resource "aws_api_gateway_deployment" "dep" {
  rest_api_id = aws_api_gateway_rest_api.api.id

  triggers = {
    redeploy = sha1(jsonencode([
      aws_api_gateway_resource.categorize.id,
      aws_api_gateway_method.post_categorize.id,
      aws_api_gateway_integration.lambda.id,
      aws_api_gateway_model.categorize_request.schema,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }

  depends_on = [aws_api_gateway_integration.lambda]
}

resource "aws_api_gateway_stage" "stage" {
  stage_name    = var.stage_name
  rest_api_id   = aws_api_gateway_rest_api.api.id
  deployment_id = aws_api_gateway_deployment.dep.id

  xray_tracing_enabled = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_access.arn
    format = jsonencode({
      requestId          = "$context.requestId"
      ip                 = "$context.identity.sourceIp"
      caller             = "$context.identity.caller"
      user               = "$context.identity.user"
      requestTime        = "$context.requestTime"
      httpMethod         = "$context.httpMethod"
      resourcePath       = "$context.resourcePath"
      status             = "$context.status"
      responseLength     = "$context.responseLength"
      integrationLatency = "$context.integrationLatency"
    })
  }
}

resource "aws_api_gateway_method_settings" "throttle" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  stage_name  = aws_api_gateway_stage.stage.stage_name
  method_path = "*/*"

  settings {
    metrics_enabled        = true
    logging_level          = "ERROR"
    data_trace_enabled     = false # リクエスト/レスポンス本文はログしない（PII流出防止）
    throttling_rate_limit  = var.throttle_rate_limit
    throttling_burst_limit = var.throttle_burst_limit
  }

  # execution logs (logging_level != OFF) はアカウント共通の CloudWatch role 設定が先に必要
  depends_on = [aws_api_gateway_account.this]
}

# API Gateway が CloudWatch Logs に書くためのアカウント共通設定。
# 同一AWSアカウントに1つだけ存在できる。既存があれば var.manage_apigw_account=false にする。
resource "aws_iam_role" "apigw_cloudwatch" {
  count = var.manage_apigw_account ? 1 : 0
  name  = "${local.name}-apigw-cloudwatch"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "apigateway.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "apigw_cloudwatch" {
  count      = var.manage_apigw_account ? 1 : 0
  role       = aws_iam_role.apigw_cloudwatch[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs"
}

resource "aws_api_gateway_account" "this" {
  count               = var.manage_apigw_account ? 1 : 0
  cloudwatch_role_arn = aws_iam_role.apigw_cloudwatch[0].arn
}
