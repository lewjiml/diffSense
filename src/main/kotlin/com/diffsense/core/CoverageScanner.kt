package com.diffsense.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
 * v0.9.0 优化：需求较多时按 [scanConcurrency] 分批并发调用 LLM，
 * 需求总数 ≤ 并发度时自动降为单批（无协程开销）。
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
     * v0.9.0 改进：需求按 [DiffSenseConfig.scanConcurrency] 分批并发扫描。
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
        log.info("[scan] start: ${requirements.size} 条需求, diff=${diff.length}B, concurrency=${config.scanConcurrency}")
        onProgress?.invoke("▶ 开始扫描：${requirements.size} 条需求，diff ${diff.length} 字符，并发度=${config.scanConcurrency}")

        indicator?.apply {
            text = "扫描代码覆盖度..."
            checkCanceled()
        }

        if (requirements.isEmpty()) {
            onProgress?.invoke("■ 无需求，跳过扫描")
            return ScanReport(
                module = module,
                baseBranch = "HEAD",
                timestamp = System.currentTimeMillis(),
                results = emptyList(),
                summary = ScanReport.Summary(0, 0, 0, 0, 0.0),
            )
        }

        val concurrency = config.scanConcurrency.coerceAtLeast(1)
        // 按 scanConcurrency 拆批：10 条 / 并发度 3 → [[1,2,3],[4,5,6],[7,8,9],[10]]
        val batches = requirements.chunked(concurrency)

        val allResults: List<CoverageResult> = if (batches.size <= 1) {
            // 需求数 ≤ 并发度：单批同步调用，避免协程开销
            onProgress?.invoke("• 需求数 ≤ 并发度（$concurrency），单批扫描")
            scanBatch(requirements, diff, onProgress, 1, 1)
        } else {
            // 需求较多：分批并发
            onProgress?.invoke("• 需求较多，拆为 ${batches.size} 批并发扫描（每批最多 $concurrency 条）")
            val semaphore = Semaphore(concurrency)
            val batchTotal = batches.size

            val batchResults: List<List<CoverageResult>> = runBlocking {
                coroutineScope {
                    batches.mapIndexed { idx, batch ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                indicator?.checkCanceled()
                                scanBatch(batch, diff, onProgress, idx + 1, batchTotal)
                            }
                        }
                    }.awaitAll()
                }
            }
            batchResults.flatten()
        }

        // 逐条输出覆盖情况
        allResults.forEach { r ->
            val tag = if (r.covered) "✓" else "✗"
            onProgress?.invoke("  $tag ${r.id} ${r.confidence}：${r.evidence.ifBlank { r.gap }}")
        }

        val summary = buildSummary(allResults)
        onProgress?.invoke("■ 扫描完成：覆盖 ${summary.covered}/${summary.total}，部分 ${summary.partial}，未覆盖 ${summary.uncovered}，覆盖率 ${"%.0f".format(summary.coverageRate * 100)}%")

        log.info("[scan] done: covered=${summary.covered}/${summary.total}, rate=${summary.coverageRate}")

        return ScanReport(
            module = module,
            baseBranch = "HEAD",
            timestamp = System.currentTimeMillis(),
            results = allResults,
            summary = summary,
        )
    }

    /**
     * 扫描单个批次（同步调用一次 LLM）
     *
     * @param batch      本批需求
     * @param diff       完整 git diff（每批都需要完整 diff 作为匹配上下文）
     * @param onProgress 进度回调
     * @param batchIndex 当前批次序号（1-based）
     * @param batchTotal 总批次数
     */
    private fun scanBatch(
        batch: List<Requirement>,
        diff: String,
        onProgress: ((String) -> Unit)?,
        batchIndex: Int,
        batchTotal: Int,
    ): List<CoverageResult> {
        onProgress?.invoke("→ [批次 $batchIndex/$batchTotal] 扫描 ${batch.size} 条需求...")
        indicator?.apply {
            text = "扫描覆盖度（批次 $batchIndex/$batchTotal）"
            checkCanceled()
        }

        val userMsg = buildScanMessage(batch, diff)
        val content = llm.chat(config.scanPrompt, userMsg, indicator)
        onProgress?.invoke("• [批次 $batchIndex/$batchTotal] LLM 返回 ${content.length} 字符，解析中...")
        return parseCoverageJson(content, batch)
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
