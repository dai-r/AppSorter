locals {
  name = var.project

  # Cross-region inference profile ARN
  bedrock_inference_profile_arn = "arn:aws:bedrock:${var.aws_region}:${data.aws_caller_identity.current.account_id}:inference-profile/${var.bedrock_model_id}"

  # プロファイルがルーティングし得る各リージョンのfoundation model ARN
  # IAMはプロファイル本体 + 配下モデル両方への許可が必要
  # `jp.*` は ap-northeast-1, ap-northeast-3 にのみルーティング
  bedrock_underlying_model_id = replace(var.bedrock_model_id, "/^(jp\\.|apac\\.|global\\.|us\\.|eu\\.)/", "")
  bedrock_routing_regions     = ["ap-northeast-1", "ap-northeast-3"]
  bedrock_foundation_model_arns = [
    for r in local.bedrock_routing_regions :
    "arn:aws:bedrock:${r}::foundation-model/${local.bedrock_underlying_model_id}"
  ]
}

data "aws_caller_identity" "current" {}
