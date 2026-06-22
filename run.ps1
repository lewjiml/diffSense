# AI 初审 Demo —— Windows 一键演示脚本
# 用法：在项目根目录执行 .\run.ps1

# 设置 UTF-8 编码，避免中文乱码
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

# 检查 .env
if (-not (Test-Path ".env")) {
    Write-Host "⚠️  未找到 .env 文件！" -ForegroundColor Yellow
    Write-Host "   请复制 .env.example 为 .env 并填入你的 AI API 配置。" -ForegroundColor Yellow
    Write-Host ""
    if (Test-Path ".env.example") {
        $copy = Read-Host "是否现在复制 .env.example 为 .env？(y/n)"
        if ($copy -eq "y" -or $copy -eq "Y") {
            Copy-Item ".env.example" ".env"
            Write-Host "✅ 已复制，请编辑 .env 填入配置后重新运行。" -ForegroundColor Green
        }
    }
    exit 1
}

# 检查 Node.js
try {
    $nodeVersion = node --version
} catch {
    Write-Host "❌ 未检测到 Node.js，请先安装。" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "  AI 初审 Demo —— 在测试验收前拦截代码缺陷" -ForegroundColor Cyan
Write-Host "  Node: $nodeVersion" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

while ($true) {
    Write-Host ""
    Write-Host "请选择演示场景：" -ForegroundColor Yellow
    Write-Host "  [1] 审查 samples/UserService.java    （Java 服务类，含多种缺陷）"
    Write-Host "  [2] 审查 samples/OrderController.java（Java 控制器，含多种缺陷）"
    Write-Host "  [3] 审查整个 samples 目录"
    Write-Host "  [4] 退出"
    Write-Host ""

    $choice = Read-Host "请输入序号 (1-4)"

    switch ($choice) {
        "1" {
            Write-Host "`n🔍 开始审查 UserService.java ...`n" -ForegroundColor Green
            node ai-review.js samples/UserService.java
        }
        "2" {
            Write-Host "`n🔍 开始审查 OrderController.java ...`n" -ForegroundColor Green
            node ai-review.js samples/OrderController.java
        }
        "3" {
            Write-Host "`n🔍 开始审查 samples 目录 ...`n" -ForegroundColor Green
            node ai-review.js samples
        }
        "4" {
            Write-Host "`n再见！`n" -ForegroundColor Cyan
            exit 0
        }
        default {
            Write-Host "无效输入，请重新选择。" -ForegroundColor Red
        }
    }

    Write-Host ""
    $continue = Read-Host "是否继续演示？(y/n)"
    if ($continue -ne "y" -and $continue -ne "Y") {
        Write-Host "`n再见！`n" -ForegroundColor Cyan
        exit 0
    }
}
