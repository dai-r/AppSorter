data "archive_file" "lambda_zip" {
  type        = "zip"
  source_dir  = "${path.module}/lambda"
  output_path = "${path.root}/build/lambda.zip"
}

resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${local.name}-categorizer"
  retention_in_days = var.log_retention_days
}

resource "aws_iam_role" "lambda_exec" {
  name = "${local.name}-lambda-exec"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "lambda_logs" {
  name = "${local.name}-lambda-logs"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ]
      Resource = "${aws_cloudwatch_log_group.lambda.arn}:*"
    }]
  })
}

resource "aws_iam_role_policy" "lambda_bedrock" {
  name = "${local.name}-lambda-bedrock"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["bedrock:InvokeModel"]
      Resource = concat(
        [local.bedrock_inference_profile_arn],
        local.bedrock_foundation_model_arns,
      )
    }]
  })
}

resource "aws_iam_role_policy" "lambda_ddb" {
  name = "${local.name}-lambda-ddb"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "dynamodb:UpdateItem",
        "dynamodb:ConditionCheckItem",
      ]
      Resource = [aws_dynamodb_table.rate_limit.arn]
    }]
  })
}

resource "aws_lambda_function" "categorizer" {
  function_name    = "${local.name}-categorizer"
  filename         = data.archive_file.lambda_zip.output_path
  source_code_hash = data.archive_file.lambda_zip.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "handler.lambda_handler"
  runtime          = "python3.12"
  architectures    = ["arm64"]
  timeout          = var.lambda_timeout_sec
  memory_size      = var.lambda_memory_mb

  reserved_concurrent_executions = var.lambda_reserved_concurrency

  environment {
    variables = {
      BEDROCK_MODEL_ID           = var.bedrock_model_id
      MAX_APPS_PER_REQUEST       = tostring(var.max_apps_per_request)
      RATE_LIMIT_TABLE           = aws_dynamodb_table.rate_limit.name
      DAILY_LIMIT_PER_IDENTITY   = tostring(var.daily_limit_per_identity)
      MONTHLY_LIMIT_PER_IDENTITY = tostring(var.monthly_limit_per_identity)
      GLOBAL_DAILY_LIMIT         = tostring(var.global_daily_limit)
      LOG_LEVEL                  = "INFO"
    }
  }

  tracing_config {
    mode = "Active"
  }

  depends_on = [
    aws_cloudwatch_log_group.lambda,
    aws_iam_role_policy.lambda_logs,
    aws_iam_role_policy.lambda_bedrock,
    aws_iam_role_policy.lambda_ddb,
  ]
}
