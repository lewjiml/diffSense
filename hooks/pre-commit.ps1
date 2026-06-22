# AI 初审 Git Hook —— pre-commit (Windows, PowerShell 版)
# 安装：将本文件内容写入 .git\hooks\pre-commit （无扩展名）
#   或在 .git\hooks\ 下创建 pre-commit 调用本脚本
# 跳过：git commit --no-verify

# 设置 UTF-8
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = "Stop"

# 支持的代码扩展名
$codeExts = @(".java",".py",".js",".ts",".go",".rs",".c",".cpp",".h",".jsx",".tsx",".kt",".scala",".rb",".php",".cs",".vue")

# 找到 ai-review.js
$repoRoot = git rev-parse --show-toplevel 2>$null
if (-not $repoRoot) { $repoRoot = (Get-Location).Path }

$aiReview = Join-Path $repoRoot "ai-review.js"
if (-not (Test-Path $aiReview)) {
    # 尝试 hooks 上一级
    $aiReview = Join-Path (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)) "ai-review.js"
}

if (-not (Test-Path $aiReview)) {
    Write-Host "⚠️  未找到 ai-review.js，跳过 AI 初审。"
    exit 0
}

# 获取暂存区文件
$staged = git diff --cached --name-only --diff-filter=ACM
if (-not $staged) { exit 0 }

# 按扩展名过滤
$filtered = @()
foreach ($f in $staged) {
    $ext = [System.IO.Path]::GetExtension($f).ToLower()
    if ($codeExts -contains $ext) {
        $filtered += $f
    }
}

if ($filtered.Count -eq 0) { exit 0 }

Write-Host "🔍 AI 初审：检查 $($filtered.Count) 个暂存文件..."

# 逐文件审查
$hasBlocking = $false
$tmpFile = [System.IO.Path]::GetTempFileName()

try {
    foreach ($f in $filtered) {
        # 取暂存区 diff
        git diff --cached -- $f | Out-File -FilePath $tmpFile -Encoding utf8
        $size = (Get-Item $tmpFile).Length
        if ($size -gt 0) {
            Write-Host ""
            Write-Host "── $f ──"
            node $aiReview --diff $tmpFile
            if ($LASTEXITCODE -ne 0) {
                $hasBlocking = $true
            }
        }
    }
} finally {
    Remove-Item $tmpFile -ErrorAction SilentlyContinue
}

if ($hasBlocking) {
    Write-Host ""
    Write-Host "❌ AI 初审发现阻断级问题，已阻止提交。" -ForegroundColor Red
    Write-Host "   修复后重试，或使用 git commit --no-verify 跳过审查。" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "✅ AI 初审通过。" -ForegroundColor Green
exit 0
