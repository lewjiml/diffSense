#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI 初审 —— CI/CD 集成配置示例

本文件演示如何在 CI 流水线中集成 AI 初审。
包含 GitLab CI 和 GitHub Actions 两套配置（以注释和 YAML 片段形式给出）。

使用前请在 CI/CD Variables 中配置：
  AI_BASE_URL  —— 大模型 API 地址（OpenAI 兼容）
  AI_API_KEY   —— API Key
  AI_MODEL     —— 模型名称
"""

# =====================================================================
# 方案 1：GitLab CI
# =====================================================================
# 将以下 YAML 片段加入项目的 .gitlab-ci.yml
#
# stages:
#   - test
#
# ai-review:
#   stage: test
#   image: python:3.11-slim
#   only:
#     - merge_requests        # 只在 MR 时触发
#   before_script:
#     - apt-get update -qq && apt-get install -y -qq git
#     - pip install openai python-dotenv
#   script:
#     - |
#       # 获取 MR 的 diff
#       git fetch origin $CI_MERGE_REQUEST_TARGET_BRANCH_NAME
#       git diff origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME...HEAD > /tmp/mr.diff
#     - |
#       # 运行 AI 初审
#       export AI_BASE_URL=$CI_AI_BASE_URL
#       export AI_API_KEY=$CI_AI_API_KEY
#       export AI_MODEL=$CI_AI_MODEL
#       python ai_review.py --diff /tmp/mr.diff
#   variables:
#     GIT_STRATEGY: clone
#     GIT_DEPTH: "0"
#   # 审查脚本发现阻断级问题会 exit 1，流水线会 fail

# =====================================================================
# 方案 2：GitHub Actions
# =====================================================================
# 将以下 YAML 保存为 .github/workflows/ai-review.yml
#
# name: AI 初审
#
# on:
#   pull_request:
#     types: [opened, synchronize, reopened]
#
# jobs:
#   ai-review:
#     runs-on: ubuntu-latest
#     steps:
#       - uses: actions/checkout@v4
#         with:
#           fetch-depth: 0
#
#       - name: Setup Python
#         uses: actions/setup-python@v5
#         with:
#           python-version: "3.11"
#
#       - name: Install dependencies
#         run: pip install openai python-dotenv
#
#       - name: Get PR diff
#         run: |
#           git fetch origin ${{ github.base_ref }}
#           git diff origin/${{ github.base_ref }}...HEAD > /tmp/pr.diff
#
#       - name: Run AI Review
#         env:
#           AI_BASE_URL: ${{ secrets.AI_BASE_URL }}
#           AI_API_KEY: ${{ secrets.AI_API_KEY }}
#           AI_MODEL: ${{ secrets.AI_MODEL }}
#         run: python ai_review.py --diff /tmp/pr.diff

# =====================================================================
# 方案 3：直接在本脚本中实现 CI 集成逻辑（可选）
# =====================================================================
# 如果你不想用 ai_review.py，也可以直接用本脚本。下面是一个
# 简化的 CI 调用入口，从环境变量读配置，审查 MR/PR 的 diff。

import os
import sys
import subprocess
import tempfile
from pathlib import Path


def get_diff_against_base(base_branch: str) -> str:
    """获取当前分支相对 base 分支的 diff"""
    try:
        subprocess.run(
            ["git", "fetch", "origin", base_branch],
            check=True, capture_output=True,
        )
        result = subprocess.run(
            ["git", "diff", f"origin/{base_branch}...HEAD"],
            check=True, capture_output=True, text=True,
        )
        return result.stdout
    except subprocess.CalledProcessError as e:
        print(f"❌ 获取 diff 失败：{e}", file=sys.stderr)
        sys.exit(2)


def main():
    base = os.environ.get("AI_REVIEW_BASE_BRANCH", "develop")
    review_script = Path(__file__).resolve().parent.parent / "ai_review.py"

    if not review_script.exists():
        print(f"❌ 未找到审查脚本：{review_script}", file=sys.stderr)
        sys.exit(2)

    print(f"🔍 CI 模式：获取相对 {base} 的代码改动...")
    diff = get_diff_against_base(base)

    if not diff.strip():
        print("ℹ️  没有代码改动，跳过审查。")
        return

    # 写入临时文件
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".diff", delete=False, encoding="utf-8"
    ) as f:
        f.write(diff)
        tmp = f.name

    try:
        result = subprocess.run(
            [sys.executable, str(review_script), "--diff", tmp],
        )
        sys.exit(result.returncode)
    finally:
        Path(tmp).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
