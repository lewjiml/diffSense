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
    private val config: DiffSenseConfig,
    private val indicator: ProgressIndicator? = null,
) {

    private val log = logger<CoverageScanner>()
    private val llm = LLMClient(config, TokenStats.Stage.SCAN)
    private val gson = Gson()

    /**
     * 扫描需求覆盖度
     *
     * 问题 5a 改进：移除 baseBranch 参数，diff 由调用方（DiffCollector）预先收集。
     *
     * @param requirements 需求列表（来自 parse 阶段或读取的 JSON）
     * @param diff         代码改动（git diff 输出，已聚合所有仓库）
     * @param module       模块名
     * @param onProgress   实时进度回调（可选，用于日志推送）
     */
    fun scan(
        requirements: List<Requirement>,
        diff: String,
        module: String,
        onProgress: ((String) -> Unit)? = null,
    ): ScanReport {
        log.info("[scan] start: ${requirements.size} 条需求, diff=${diff.length}B")
        onProgress?.invoke("▶ 开始扫描：${requirements.size} 条需求，diff ${diff.length} 字符")

        indicator?.apply {
            text = "扫描代码覆盖度..."
            checkCanceled()
        }

        onProgress?.invoke("• 组装提示词，包含需求列表与 git diff...")
        val userMsg = buildScanMessage(requirements, diff)
        onProgress?.invoke("• 调用 LLM（${config.model}）...")
        val content = llm.chat(config.scanPrompt, userMsg, indicator)
        onProgress?.invoke("• LLM 返回 ${content.length} 字符，解析中...")

        val results = parseCoverageJson(content, requirements)
        val summary = buildSummary(results)

        // 逐条输出覆盖情况
        results.forEach { r ->
            val tag = if (r.covered) "✓" else "✗"
            onProgress?.invoke("  $tag ${r.id} ${r.confidence}：${r.evidence.ifBlank { r.gap }}")
        }
        onProgress?.invoke("■ 扫描完成：覆盖 ${summary.covered}/${summary.total}，部分 ${summary.partial}，未覆盖 ${summary.uncovered}，覆盖率 ${"%.0f".format(summary.coverageRate * 100)}%")

        log.info("[scan] done: covered=${summary.covered}/${summary.total}, rate=${summary.coverageRate}")

        return ScanReport(
            module = module,
            baseBranch = "HEAD",
            timestamp = System.currentTimeMillis(),
            results = results,
            summary = summary,
        )
    }

    /** 构建覆盖度分析的用户消息（改动 3b：代码改动在前，需求在后，强化「从代码出发」） */
    private fun buildScanMessage(requirements: List<Requirement>, diff: String): String {
        return buildString {
            appendLine("## 代码改动（git diff，请逐块分析）")
            appendLine("注意：以 `diff --git a/... b/...` 开头的行标注了文件路径，是判断业务归属的重要线索")
            appendLine()
            appendLine("```diff")
            appendLine(diff)
            appendLine("```")
            appendLine()
            appendLine("## 需求列表（共 ${requirements.size} 条，供匹配参考）")
            appendLine()
            requirements.forEach { r ->
                appendLine("- **${r.id}** ${r.title}")
                if (r.description.isNotBlank()) {
                    appendLine("  ${r.description}")
                }
                if (r.acceptance.isNotEmpty()) {
                    appendLine("  验收标准：")
                    r.acceptance.forEach { appendLine("    - $it") }
                }
            }
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
