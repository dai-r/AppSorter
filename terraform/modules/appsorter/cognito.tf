resource "aws_cognito_identity_pool" "pool" {
  identity_pool_name               = "${local.name}_identity"
  allow_unauthenticated_identities = true
  allow_classic_flow               = false
}

resource "aws_iam_role" "cognito_unauth" {
  name = "${local.name}-cognito-unauth"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = "cognito-identity.amazonaws.com" }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "cognito-identity.amazonaws.com:aud" = aws_cognito_identity_pool.pool.id
        }
        "ForAnyValue:StringLike" = {
          "cognito-identity.amazonaws.com:amr" = "unauthenticated"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "cognito_unauth_invoke" {
  name = "${local.name}-cognito-unauth-invoke"
  role = aws_iam_role.cognito_unauth.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "execute-api:Invoke"
      Resource = "${aws_api_gateway_rest_api.api.execution_arn}/${var.stage_name}/POST/categorize"
    }]
  })
}

resource "aws_cognito_identity_pool_roles_attachment" "roles" {
  identity_pool_id = aws_cognito_identity_pool.pool.id

  roles = {
    unauthenticated = aws_iam_role.cognito_unauth.arn
  }
}
