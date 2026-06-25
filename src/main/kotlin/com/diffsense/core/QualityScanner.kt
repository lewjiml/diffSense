package com.diffsense.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator

/**
 * 代码质量扫描器（quality 阶段，v0.8.0 新增）
 *
 * 与 [CoverageScanner] 并行存在，但聚焦点不同：
 * - CoverageScanner：判断「代码是否覆盖了需求」
 * - QualityScanner ：判断「代码本身有没有 bug / 安全 / 性能 / 异味」
 *
 * 工作流：
 *   Git Diff
 *     → 组装 prompt（仅 diff，不依赖需求文档）
 *     → 调用 LLM 识别问题
 *     → 输出 [QualityReport]
 *
 * 独立一次 LLM 调用，prompt 精准，结果质量更高。
 *
 * @param config   配置（使用其中的 qualityPrompt 作为系统提示词）
 * @param indicator 进度指示器
 */
class QualityScanner(
    private val config: DiffSenseConfig,
    private val indicator: ProgressIndicator? = null,
) {

    private val log = logger<QualityScanner>()
    private val llm = LLMClient(config, TokenStats.Stage.QUALITY)
    private val gson = Gson()

    /**
     * 扫描代码质量
     *
     * @param diff       代码改动（git diff 输出）
     * @param onProgress 实时进度回调（可选，用于日志推送）
     * @return 质量扫描报告（无问题时返回空列表）
     */
    fun scan(
        diff: String,
        onProgress: ((String) -> Unit)? = null,
    ): QualityReport {
        log.info("[quality] start: diff=${diff.length}B")
        onProgress?.invoke("▶ 开始代码质量扫描：diff ${diff.length} 字符")

        indicator?.apply {
            text = "扫描代码质量..."
            checkCanceled()
        }

        onProgress?.invoke("• 组装提示词，准备调用 LLM...")
        val userMsg = buildQualityMessage(diff)
        onProgress?.invoke("• 调用 LLM（${config.model}）...")
        val content = llm.chat(config.qualityPrompt, userMsg, indicator)
        onProgress?.invoke("• LLM 返回 ${content.length} 字符，解析中...")

        val issues = parseQualityJson(content)
        val sorted = sortBySeverity(issues)
        val summary = buildSummary(sorted)

        // 逐条输出问题
        sorted.forEach { issue ->
            onProgress?.invoke("  ${issue.severityText()} ${issue.categoryText()} [${issue.file}] ${issue.description.take(60)}")
        }
        onProgress?.invoke(
            "■ 质量扫描完成：共 ${summary.total()} 条问题" +
                "（高 ${summary.highCount} / 中 ${summary.mediumCount} / 低 ${summary.lowCount}）"
        )

        log.info("[quality] done: ${summary.total()} issues (high=${summary.highCount}, medium=${summary.mediumCount}, low=${summary.lowCount})")

        return QualityReport(
            issues = sorted,
            summary = summary,
        )
    }

    /** 构建代码质量扫描的用户消息 */
    private fun buildQualityMessage(diff: String): String {
        return buildString {
            appendLine("## 请审查以下 git diff 代码改动")
            appendLine("注意：以 `diff --git a/... b/...` 开头的行标注了文件路径")
            appendLine()
            appendLine("```diff")
            appendLine(diff)
            appendLine("```")
        }
    }

    /**
     * 解析质量问题 JSON 数组
     *
     * LLM 应返回：
     * [
     *   { "severity":"high","category":"bug","file":"...","lineHint":"...","description":"...","suggestion":"..." }
     * ]
     *
     * 无问题时返回 []。
     */
    private fun parseQualityJson(content: String): List<QualityIssue> {
        val json = extractJsonArray(content) ?: run {
            log.warn("[quality] 未找到 JSON 数组，原文：${content.take(200)}")
            return emptyList()
        }

        return try {
            val type = object : TypeToken<List<QualityIssue>>() {}.type
            gson.fromJson<List<QualityIssue>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            log.warn("[quality] JSON 解析失败：${e.message}")
            emptyList()
        }
    }

    /** 从 LLM 输出中提取 JSON 数组（兼容 markdown 代码块包裹） */
    private fun extractJsonArray(content: String): String? {
        val trimmed = content.trim()
        if (trimmed.startsWith("[")) return trimmed
        // 兼容 ```json ... ``` 包裹
        val codeBlock = Regex("""```(?:json)?\s*(.+?)\s*```""", RegexOption.DOT_MATCHES_ALL)
            .find(trimmed)
        if (codeBlock != null) {
            val inner = codeBlock.groupValues[1].trim()
            if (inner.startsWith("[")) return inner
        }
        val first = trimmed.indexOf('[')
        val last = trimmed.lastIndexOf(']')
        if (first in 0 until last) {
            return trimmed.substring(first, last + 1)
        }
        return null
    }

    /** 按严重度排序：high → medium → low */
    private fun sortBySeverity(issues: List<QualityIssue>): List<QualityIssue> {
        val rank = mapOf("high" to 0, "medium" to 1, "low" to 2)
        return issues.sortedBy { rank[it.severity] ?: 3 }
    }

    private fun buildSummary(issues: List<QualityIssue>): QualitySummary {
        return QualitySummary(
            highCount = issues.count { it.severity == "high" },
            mediumCount = issues.count { it.severity == "medium" },
            lowCount = issues.count { it.severity == "low" },
        )
    }
}
