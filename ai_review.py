#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI 初审工具 —— 在测试验收前拦截代码缺陷
核心审查脚本（Python 版，使用 openai + python-dotenv）

用法：
  python ai_review.py <文件或目录>              # 审查代码文件
  python ai_review.py --diff <diff文件>         # 审查 git diff
  python ai_review.py --stdin                   # 从标准输入读取
  git diff HEAD~1 | python ai_review.py --stdin # 审查最近一次提交
"""

import os
import sys
import json
import argparse
from pathlib import Path

# Windows 终端 UTF-8
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

# ==================== 加载 .env ====================

try:
    from dotenv import load_dotenv
    load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"))
except ImportError:
    # 没有 python-dotenv 时手动读取
    env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if os.path.exists(env_path):
        with open(env_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                key = key.strip()
                val = val.strip().strip('"').strip("'")
                if key and key not in os.environ:
                    os.environ[key] = val

BASE_URL = os.environ.get("AI_BASE_URL", "https://api.openai.com/v1")
API_KEY = os.environ.get("AI_API_KEY", "")
MODEL = os.environ.get("AI_MODEL", "claude-sonnet-4-20250514")
SEVERITY = os.environ.get("REVIEW_SEVERITY", "normal").lower()

# ==================== 配置 ====================

CODE_EXTENSIONS = {
    ".java", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h",
    ".jsx", ".tsx", ".kt", ".scala", ".rb", ".php", ".cs", ".vue",
}

SKIP_DIRS = {
    "node_modules", ".git", "target", "build", "dist",
    "__pycache__", ".idea", "venv",
}

# ==================== Prompt ====================

SYSTEM_PROMPT = """你是资深代码审查专家，严格按照企业级开发规范审查代码。
你的目标是：在代码进入测试验收之前，提前发现并拦截缺陷。

审查维度（逐项检查）：
1. 语法错误 —— 编译能过但逻辑有误（如赋值写成比较）
2. 空指针/空值风险 —— 未判空就调用方法、集合可能为空
3. 未捕获异常 —— try-catch 缺失、catch了不处理直接吞掉
4. 入参校验缺失 —— 外部输入未校验（类型、长度、范围、格式）
5. 命名规范 —— 变量名/方法名是否清晰、符合团队规范
6. 代码重复 —— 复制粘贴的逻辑、可抽取的公共方法
7. 性能隐患 —— 循环内查数据库、N+1查询、大对象未释放、资源泄漏
8. 安全风险 —— SQL拼接注入、敏感信息打印日志、硬编码密码/token
9. 业务逻辑风险 —— if/else分支遗漏、边界条件未处理、并发问题

输出要求（严格遵守）：
- 只报告有问题的项，不要输出"没有问题"之类的废话
- 每个问题按以下格式输出：

【级别】致命 / 严重 / 一般 / 建议
【位置】行号或代码片段
【维度】属于上面9项中的哪一项
【原因】一句话说明问题
【修复】给出修复后的代码片段

级别说明：
- 致命：会导致崩溃、数据丢失、安全漏洞 —— 必须修复
- 严重：会导致功能错误、边界异常 —— 强烈建议修复
- 一般：代码质量、可维护性问题 —— 建议修复
- 建议：优化建议、风格改进 —— 可选"""

DIFF_SYSTEM_PROMPT = """你是资深代码审查专家，正在审查一个 Pull Request / Merge Request 的代码变更。
你的目标是：在变更合并到主分支前，提前发现引入的缺陷。

审查重点：
1. 这次改动是否引入了新的 bug 或风险
2. 新增/修改代码是否有空指针、异常未捕获等问题
3. 是否破坏了已有逻辑（删错了、改错了）
4. 是否有安全风险（SQL注入、敏感信息泄露）
5. 边界条件和异常分支是否覆盖
6. 新增代码是否符合现有风格

输出要求同上。只报告有问题的项。
如果未发现阻断级问题，在开头明确输出：✅ 未发现阻断级问题"""

# ==================== 调用大模型 ====================

_client = None

def get_client():
    global _client
    if _client is None:
        try:
            from openai import OpenAI
        except ImportError:
            print("❌ 未安装 openai 库，请执行：pip install openai python-dotenv", file=sys.stderr)
            sys.exit(2)
        _client = OpenAI(base_url=BASE_URL, api_key=API_KEY)
    return _client

def review_code(code, filename, is_diff=False):
    """调用大模型审查代码，返回原始报告文本"""
    client = get_client()
    system_prompt = DIFF_SYSTEM_PROMPT if is_diff else SYSTEM_PROMPT
    if is_diff:
        user_msg = f"请审查以下代码变更（git diff 格式）：\n\n文件：{filename}\n```diff\n{code}\n```"
    else:
        user_msg = f"请审查以下代码：\n\n文件：{filename}\n```\n{code}\n```"

    resp = client.chat.completions.create(
        model=MODEL,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_msg},
        ],
        temperature=0.1,
        max_tokens=4096,
    )
    return resp.choices[0].message.content

# ==================== 文件收集 ====================

def collect_files(target_path, result=None):
    if result is None:
        result = []
    p = Path(target_path)
    if p.is_file():
        if p.suffix.lower() in CODE_EXTENSIONS:
            result.append(str(p))
        return result
    if p.is_dir():
        for child in p.iterdir():
            if child.name in SKIP_DIRS:
                continue
            collect_files(child, result)
    return result

# ==================== 严重度过滤 ====================

LEVEL_RANK = {"致命": 4, "严重": 3, "一般": 2, "建议": 1}
SEVERITY_THRESHOLD = {"strict": 1, "normal": 2, "relaxed": 3}

def filter_by_severity(report):
    """按级别分块过滤，返回 (content, has_blocking)"""
    threshold = SEVERITY_THRESHOLD.get(SEVERITY, 2)
    lines = report.splitlines()
    blocks = []
    header = []
    current = []
    in_header = True

    for line in lines:
        if "【级别】" in line:
            if in_header and current:
                header = current[:]
                current = []
            elif current:
                blocks.append("\n".join(current))
                current = []
            in_header = False
        current.append(line)

    if current:
        if in_header:
            header = current[:]
        else:
            blocks.append("\n".join(current))

    kept = []
    has_blocking = False
    import re
    for block in blocks:
        m = re.search(r"【级别】\s*(\S+)", block)
        if not m:
            kept.append(block)
            continue
        level = m.group(1).strip()
        rank = LEVEL_RANK.get(level, 0)
        if rank >= threshold:
            kept.append(block)
            if rank >= 3:
                has_blocking = True

    content = "\n".join([*header, *kept]).strip()
    return content, has_blocking

# ==================== 审查单个文件 ====================

def run_review(filepath, is_diff=False):
    """审查单个文件，返回 has_blocking"""
    with open(filepath, "r", encoding="utf-8") as f:
        code = f.read()
    filename = os.path.basename(filepath)
    raw = review_code(code, filename, is_diff=is_diff)
    content, has_blocking = filter_by_severity(raw)

    tag = "❌ 发现阻断级问题" if has_blocking else "✅ 未发现阻断级问题"
    print(f"\n{'─' * 60}")
    print(f"  审查文件：{filename}    {tag}")
    print(f"{'─' * 60}")
    if content:
        print(content)
    else:
        print("（过滤后无需要报告的问题）")
    return has_blocking

def run_review_content(content, label, is_diff=True):
    """审查文本内容（stdin / diff），返回 has_blocking"""
    raw = review_code(content, label, is_diff=is_diff)
    filtered, has_blocking = filter_by_severity(raw)
    tag = "❌ 发现阻断级问题" if has_blocking else "✅ 未发现阻断级问题"
    print(f"\n{'─' * 60}")
    print(f"  审查内容：{label}    {tag}")
    print(f"{'─' * 60}")
    if filtered:
        print(filtered)
    else:
        print("（过滤后无需要报告的问题）")
    return has_blocking

# ==================== main ====================

def main():
    parser = argparse.ArgumentParser(
        description="AI 初审工具 —— 在测试验收前拦截代码缺陷",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
示例：
  python ai_review.py samples/UserService.java
  python ai_review.py --diff changes.diff
  git diff HEAD~1 | python ai_review.py --stdin
""",
    )
    parser.add_argument("path", nargs="?", help="文件或目录路径")
    parser.add_argument("--diff", metavar="FILE", help="审查 git diff 文件")
    parser.add_argument("--stdin", action="store_true", help="从标准输入读取")
    args = parser.parse_args()

    if not args.path and not args.diff and not args.stdin:
        parser.print_help()
        sys.exit(0)

    if not API_KEY:
        print("❌ 未配置 AI_API_KEY，请在 .env 中设置。可参考 .env.example。", file=sys.stderr)
        sys.exit(2)

    has_blocking = False

    try:
        if args.stdin:
            data = sys.stdin.read()
            if not data.strip():
                print("❌ 标准输入为空", file=sys.stderr)
                sys.exit(2)
            has_blocking = run_review_content(data, "stdin", is_diff=True)
        elif args.diff:
            if not os.path.exists(args.diff):
                print(f"❌ 文件不存在：{args.diff}", file=sys.stderr)
                sys.exit(2)
            with open(args.diff, "r", encoding="utf-8") as f:
                has_blocking = run_review_content(f.read(), os.path.basename(args.diff), is_diff=True)
        else:
            if not os.path.exists(args.path):
                print(f"❌ 路径不存在：{args.path}", file=sys.stderr)
                sys.exit(2)
            files = collect_files(args.path)
            if not files:
                print(f"❌ 未找到支持的代码文件", file=sys.stderr)
                sys.exit(2)
            print(f"🔍 共发现 {len(files)} 个代码文件，开始审查...\n")
            for f in files:
                try:
                    if run_review(f, is_diff=False):
                        has_blocking = True
                except Exception as e:
                    print(f"\n❌ 审查 {os.path.basename(f)} 失败：{e}", file=sys.stderr)

        print(f"\n{'═' * 60}")
        if has_blocking:
            print("  ❌ 审查完成，发现阻断级问题，请修复后再提交！")
            sys.exit(1)
        else:
            print("  ✅ 审查完成，未发现阻断级问题。")
            sys.exit(0)
    except Exception as e:
        print(f"\n💥 运行出错：{e}", file=sys.stderr)
        sys.exit(2)

if __name__ == "__main__":
    main()
