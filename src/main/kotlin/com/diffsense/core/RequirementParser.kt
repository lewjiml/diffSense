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
 * 需求拆解器（parse 阶段）
 *
 * 对应 ai-req.js 中的 parseRequirements() 函数。
 *
 * 工作流：
 *   需求文档 MD
 *     → [MarkdownSplitter] 按 ## 切片
 *     → 按用户选择的板块/子板块过滤
 *     → 对每个片段调用 LLM 拆解（并发，受 [DiffSenseConfig.parseConcurrency] 限制）
 *     → 合并成 RequirementDocument
 *
 * @param config   配置
 * @param indicator 进度指示器（支持取消）
 */
class RequirementParser(
    private val config: DiffSenseConfig,
    private val indicator: ProgressIndicator? = null,
) {

    private val log = logger<RequirementParser>()
    private val llm = LLMClient(config, TokenStats.Stage.PARSE)
    private val gson = Gson()

    /**
     * 解析需求文档
     *
     * @param md            markdown 原文
     * @param module        模块名（用于输出元信息）
     * @param sectionFilter 要处理的板块标题（null/empty = 全部）
     * @param subSectionFilter 要处理的子板块标题（null/empty = 全部）
     * @param onProgress    进度回调（可选，用于实时日志推送）
     * @return 结构化需求文档
     */
    fun parse(
        md: String,
        module: String,
        sectionFilter: List<String>? = null,
        subSectionFilter: List<String>? = null,
        onProgress: ((String) -> Unit)? = null,
    ): RequirementDocument = runBlocking {
        log.info("[parse] start: module=$module, concurrency=${config.parseConcurrency}")

        val sections = MarkdownSplitter.splitByHeading(md)
            .filter { sectionFilter.isNullOrEmpty() || it.first in sectionFilter }

        // 展平成 (secTitle, subTitle, body) 任务列表
        data class Slice(val section: String, val subSection: String, val body: String)
        val slices = mutableListOf<Slice>()
        for ((secTitle, secBody) in sections) {
            val subSections = MarkdownSplitter.splitBySubHeading(secBody)
                .filter { subSectionFilter.isNullOrEmpty() || it.first in subSectionFilter }
            for ((subTitle, subBody) in subSections) {
                slices += Slice(secTitle, subTitle, subBody)
            }
        }

        val total = slices.size
        onProgress?.invoke("共 $total 个片段待拆解，并发度=${config.parseConcurrency}")
        indicator?.text = "拆解需求（0/$total）"

        // 信号量限制并发度
        val semaphore = Semaphore(config.parseConcurrency)
        val counter = java.util.concurrent.atomic.AtomicInteger(0)

        // 用 supervisor job + coroutineScope 确保一处失败能传播到整批
        val results: List<Pair<Int, List<Requirement>>> = coroutineScope {
            slices.mapIndexed { index, slice ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        indicator?.checkCanceled()
                        val userMsg = buildUserMessage(slice.section, slice.subSection, slice.body)
                        onProgress?.invoke("→ [${index + 1}/$total] 拆解：${slice.section} / ${slice.subSection}")
                        val content = llm.chat(Prompts.parseSystemPrompt, userMsg, indicator)
                        val reqs = parseRequirementsJson(content)
                        val done = counter.incrementAndGet()
                        indicator?.text = "拆解需求（$done/$total）"
                        indicator?.fraction = done.toDouble() / total
                        onProgress?.invoke("✓ [$done/$total] ${slice.section} / ${slice.subSection} → ${reqs.size} 条")
                        index to reqs
                    }
                }
            }.awaitAll()
        }

        // 按原顺序合并结果，再统一编号
        val allRequirements = results.sortedBy { it.first }
            .flatMap { it.second }
            .mapIndexed { i, r ->
                r.id = "REQ-${(i + 1).toString().padStart(3, '0')}"
                r
            }

        onProgress?.invoke("拆解完成，共 ${allRequirements.size} 条需求")

        RequirementDocument(
            module = module,
            source = "${md.length} bytes",
            section = sectionFilter?.joinToString(",") ?: "all",
            total = allRequirements.size,
            requirements = allRequirements,
        )
    }

    /** 构建给 LLM 的用户消息 */
    private fun buildUserMessage(section: String, subSection: String, body: String): String {
        return buildString {
            appendLine("## 板块：$section")
            if (subSection.isNotBlank()) {
                appendLine("## 子板块：$subSection")
            }
            appendLine()
            appendLine("请拆解以下需求文档片段：")
            appendLine()
            appendLine(body)
        }
    }

    /**
     * 解析 LLM 返回的 JSON 数组
     *
     * 兼容三种格式：
     *   - 纯 JSON 数组
     *   - markdown 代码块包裹（```json ... ```)
     *   - 带前后多余文本
     */
    private fun parseRequirementsJson(content: String): List<Requirement> {
        val json = extractJsonArray(content) ?: return emptyList()
        val type = object : TypeToken<List<Requirement>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    /** 从 LLM 输出中提取 JSON 数组部分 */
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

    /** 将 RequirementDocument 序列化为 JSON 字符串 */
    fun toJson(doc: RequirementDocument): String {
        val type = object : TypeToken<RequirementDocument>() {}.type
        return gson.toJson(doc, type)
    }

    /** 从 JSON 字符串反序列化 */
    fun fromJson(json: String): RequirementDocument {
        val type = object : TypeToken<RequirementDocument>() {}.type
        return gson.fromJson(json, type)
    }
}
