#######################
# SNS topic for budget alerts
#######################
resource "aws_sns_topic" "budget_alert" {
  name = "${local.name}-budget-alert"
}

resource "aws_sns_topic_policy" "budget_alert" {
  arn = aws_sns_topic.budget_alert.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowBudgetsPublish"
      Effect    = "Allow"
      Principal = { Service = "budgets.amazonaws.com" }
      Action    = "SNS:Publish"
      Resource  = aws_sns_topic.budget_alert.arn
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = data.aws_caller_identity.current.account_id
        }
      }
    }]
  })
}

#######################
# AWS Budget (monthly cost cap with multi-stage notifications)
#######################
resource "aws_budgets_budget" "monthly" {
  name         = "${local.name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.budget_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # 50% 予測到達: メール警告のみ
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.notification_email]
  }

  # 80% 実績到達: メール警告のみ
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.notification_email]
  }

  # 100% 実績到達: メール + SNS で kill switch 発火
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.notification_email]
    subscriber_sns_topic_arns  = [aws_sns_topic.budget_alert.arn]
  }

  depends_on = [aws_sns_topic_policy.budget_alert]
}

#######################
# Kill switch Lambda
#######################
resource "aws_cloudwatch_log_group" "killswitch" {
  name              = "/aws/lambda/${local.name}-killswitch"
  retention_in_days = var.log_retention_days
}

resource "aws_iam_role" "killswitch_exec" {
  name = "${local.name}-killswitch-exec"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "killswitch_logs" {
  name = "${local.name}-killswitch-logs"
  role = aws_iam_role.killswitch_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
      ]
      Resource = "${aws_cloudwatch_log_group.killswitch.arn}:*"
    }]
  })
}

resource "aws_iam_role_policy" "killswitch_ddb" {
  name = "${local.name}-killswitch-ddb"
  role = aws_iam_role.killswitch_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["dynamodb:UpdateItem"]
      Resource = aws_dynamodb_table.rate_limit.arn
    }]
  })
}

data "archive_file" "killswitch_zip" {
  type        = "zip"
  source_file = "${path.module}/lambda/killswitch.py"
  output_path = "${path.module}/build/killswitch.zip"
}

resource "aws_lambda_function" "killswitch" {
  function_name    = "${local.name}-killswitch"
  filename         = data.archive_file.killswitch_zip.output_path
  source_code_hash = data.archive_file.killswitch_zip.output_base64sha256
  role             = aws_iam_role.killswitch_exec.arn
  handler          = "killswitch.lambda_handler"
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
    aws_cloudwatch_log_group.killswitch,
    aws_iam_role_policy.killswitch_logs,
    aws_iam_role_policy.killswitch_ddb,
  ]
}

resource "aws_sns_topic_subscription" "killswitch" {
  topic_arn = aws_sns_topic.budget_alert.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.killswitch.arn
}

resource "aws_lambda_permission" "killswitch_sns" {
  statement_id  = "AllowSNSInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.killswitch.function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.budget_alert.arn
}
