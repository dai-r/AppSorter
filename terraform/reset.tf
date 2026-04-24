#######################
# Monthly kill switch auto-reset
#######################

resource "aws_cloudwatch_log_group" "reset" {
  name              = "/aws/lambda/${local.name}-reset"
  retention_in_days = var.log_retention_days
}

resource "aws_iam_role" "reset_exec" {
  name = "${local.name}-reset-exec"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "reset_logs" {
  name = "${local.name}-reset-logs"
  role = aws_iam_role.reset_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
      ]
      Resource = "${aws_cloudwatch_log_group.reset.arn}:*"
    }]
  })
}

resource "aws_iam_role_policy" "reset_ddb" {
  name = "${local.name}-reset-ddb"
  role = aws_iam_role.reset_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["dynamodb:UpdateItem"]
      Resource = aws_dynamodb_table.rate_limit.arn
    }]
  })
}

data "archive_file" "reset_zip" {
  type        = "zip"
  source_file = "${path.module}/lambda/reset.py"
  output_path = "${path.module}/build/reset.zip"
}

resource "aws_lambda_function" "reset" {
  function_name    = "${local.name}-reset"
  filename         = data.archive_file.reset_zip.output_path
  source_code_hash = data.archive_file.reset_zip.output_base64sha256
  role             = aws_iam_role.reset_exec.arn
  handler          = "reset.lambda_handler"
  runtime          = "python3.12"
  architectures    = ["arm64"]
  timeout          = 10
  memory_size      = 256

  environment {
    variables = {
      RATE_LIMIT_TABLE = aws_dynamodb_table.rate_limit.name
      LOG_LEVEL        = "INFO"
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.reset,
    aws_iam_role_policy.reset_logs,
    aws_iam_role_policy.reset_ddb,
  ]
}

# 月初 (UTC 00:00 on day 1) に発火。JSTでは毎月1日の 09:00。
resource "aws_cloudwatch_event_rule" "monthly_reset" {
  name                = "${local.name}-monthly-reset"
  description         = "Reset Budget kill switch on the first day of each month (UTC)"
  schedule_expression = "cron(0 0 1 * ? *)"
}

resource "aws_cloudwatch_event_target" "monthly_reset" {
  rule      = aws_cloudwatch_event_rule.monthly_reset.name
  target_id = "reset-lambda"
  arn       = aws_lambda_function.reset.arn
}

resource "aws_lambda_permission" "reset_events" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.reset.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.monthly_reset.arn
}
