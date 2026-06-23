package com.diffsense.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator

/**
 * 需求覆盖度扫描器（scan 阶段）
 *
 * 对应 ai-req.js 中的 scanCoverage() 函数。
 *
 * 工作流：
 *   需求 JSON + Git Diff
 *     → 组装 prompt（需求列表 + 代码改动）
 *     → 调用 LLM 判断覆盖度
 *     → 输出 ScanReport
 *
 * @param config   配置
 * @param indicator 进度指示器
 */
class CoverageScanner(
    config: DiffSenseConfig,
    private val indicator: ProgressIndicator? = null,
) {

    private val log = logger<CoverageScanner>()
    private val llm = LLMClient(config, TokenStats.Stage.SCAN)
    private val gson = Gson()

    /**
     * 扫描需求覆盖度
     *
     * @param requirements 需求列表（来自 parse 阶段或读取的 JSON）
     * @param diff         代码改动（git diff 输出）
     * @param module       模块名
     * @param baseBranch   基线分支
     */
    fun scan(
        requirements: List<Requirement>,
        diff: String,
        module: String,
        baseBranch: String,
    ): ScanReport {
        log.info("[scan] start: ${requirements.size} 条需求, diff=${diff.length}B")

        indicator?.apply {
            text = "扫描代码覆盖度..."
            checkCanceled()
        }

        val userMsg = buildScanMessage(requirements, diff)
        val content = llm.chat(Prompts.scanSystemPrompt, userMsg, indicator)

        val results = parseCoverageJson(content, requirements)
        val summary = buildSummary(results)

        log.info("[scan] done: covered=${summary.covered}/${summary.total}, rate=${summary.coverageRate}")

        return ScanReport(
            module = module,
            baseBranch = baseBranch,
            timestamp = System.currentTimeMillis(),
            results = results,
            summary = summary,
        )
    }

    /** 构建覆盖度分析的用户消息 */
    private fun buildScanMessage(requirements: List<Requirement>, diff: String): String {
        return buildString {
            appendLine("## 需求列表（共 ${requirements.size} 条）")
            appendLine()
            requirements.forEach { r ->
                appendLine("- **${r.id}** ${r.title} [${r.priority}]")
                appendLine("  ${r.description}")
                if (r.acceptance.isNotEmpty()) {
                    appendLine("  验收标准：")
                    r.acceptance.forEach { appendLine("    - $it") }
                }
            }
            appendLine()
            appendLine("## 代码改动（git diff）")
            appendLine()
            appendLine("```diff")
            appendLine(diff)
            appendLine("```")
        }
    }

    /**
     * 解析覆盖度结果 JSON
     *
     * 同时处理 LLM 可能漏返某些需求的情况：未返回的视为未覆盖。
     */
    private fun parseCoverageJson(
        content: String,
        requirements: List<Requirement>,
    ): List<CoverageResult> {
        val json = extractJsonArray(content) ?: return requirements.map {
            CoverageResult(
                id = it.id,
                covered = false,
                confidence = "low",
                evidence = "",
                gap = "LLM 未返回结果",
            )
        }

        val type = object : TypeToken<List<CoverageResult>>() {}.type
        val parsed: List<CoverageResult> = gson.fromJson(json, type) ?: emptyList()

        // 用 requirements 的顺序为准，缺失的补 "未覆盖"
        val byId = parsed.associateBy { it.id }
        return requirements.map { req ->
            byId[req.id] ?: CoverageResult(
                id = req.id,
                covered = false,
                confidence = "low",
                evidence = "",
                gap = "LLM 未评估此条需求",
            )
        }
    }

    private fun extractJsonArray(content: String): String? {
        val trimmed = content.trim()
        if (trimmed.startsWith("[")) return trimmed
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

    private fun buildSummary(results: List<CoverageResult>): ScanReport.Summary {
        val total = results.size
        val covered = results.count { it.covered && it.confidence == "high" }
        val partial = results.count { it.covered && it.confidence != "high" }
        val uncovered = total - covered - partial
        val rate = if (total == 0) 0.0 else covered.toDouble() / total
        return ScanReport.Summary(
            total = total,
            covered = covered,
            uncovered = uncovered,
            partial = partial,
            coverageRate = rate,
        )
    }
}
