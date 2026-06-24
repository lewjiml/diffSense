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
 * 工作流：
 *   需求文档 MD
 *     → [MarkdownSplitter] 按 ## 切片（自动剔除删除线噪声）
 *     → 按用户选择的板块关键词过滤（包含匹配）
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
     * 问题 1 改进：过滤下钻到 ### 级别（两层匹配）。
     *   - 关键词命中 ## → 拆该 ## 下所有 ###
     *   - 关键词命中 ### → 只拆那个 ###
     *
     * @param md              markdown 原文
     * @param module          模块名（用于输出元信息；若留空会从板块关键词推导）
     * @param sectionKeywords 板块过滤关键词（null/empty = 全部；两层匹配下钻到 ###）
     * @param onProgress      进度回调（可选，用于实时日志推送）
     * @return 结构化需求文档
     */
    fun parse(
        md: String,
        module: String,
        sectionKeywords: List<String>? = null,
        onProgress: ((String) -> Unit)? = null,
    ): RequirementDocument = runBlocking {
        log.info("[parse] start: module=$module, keywords=$sectionKeywords, concurrency=${config.parseConcurrency}")

        // 问题 1：使用 filterToSubSections 下钻到 ### 级别
        val subSecPairs = MarkdownSplitter.filterToSubSections(md, sectionKeywords ?: emptyList())

        // module 自动推导：留空时取第一个命中的板块标题
        val effectiveModule = module.ifBlank {
            sectionKeywords?.firstOrNull()?.trim()?.ifBlank { null }
                ?: subSecPairs.firstOrNull()?.first
                ?: "default"
        }

        // 展平成 (secTitle, subTitle, body) 任务列表
        data class Slice(val section: String, val subSection: String, val body: String)
        val slices = mutableListOf<Slice>()
        val sections = MarkdownSplitter.splitByHeading(md)
        for ((secTitle, subTitle) in subSecPairs) {
            val secBody = sections.firstOrNull { it.first == secTitle }?.second ?: continue
            if (subTitle == secTitle) {
                slices += Slice(secTitle, secTitle, secBody)
            } else {
                val subParts = MarkdownSplitter.splitBySubHeading(secBody)
                // 改动 2（v4）：既匹配 ### 标题本身，也匹配其下表格行切片（格式 "###标题 / 行内容"）
                val matched = subParts.filter {
                    it.first == subTitle || it.first.startsWith("$subTitle / ")
                }
                if (matched.isEmpty()) {
                    slices += Slice(secTitle, subTitle, secBody)
                } else {
                    matched.forEach { (_, body) ->
                        slices += Slice(secTitle, subTitle, body)
                    }
                }
            }
        }

        val total = slices.size
        onProgress?.invoke("共 $total 个片段待拆解，并发度=${config.parseConcurrency}（已自动剔除删除线内容）")
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
                        val reqs = parseRequirementsJson(content, onProgress, "${slice.section}/${slice.subSection}")
                        // 改动 7b（v4）：回填 source，记录需求来自文档的哪个板块/子板块
                        val sourcePath = if (slice.subSection.isNotBlank()) "${slice.section} / ${slice.subSection}" else slice.section
                        reqs.forEach { it.source = sourcePath }
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
            module = effectiveModule,
            source = "${md.length} bytes",
            section = sectionKeywords?.joinToString(",") ?: "all",
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
     * 解析 LLM 返回的 JSON 数组（三层容错）
     *
     * 容错策略：
     *   第一层：extractJsonArray 定位顶层数组并判定是否截断（括号深度计数）
     *   第二层：repairTruncatedJson 修复尾部截断（跳过外层 [ 做深度计数）
     *   第三层：parseObjectsIndividually 逐对象兜底（整体解析失败时降级）
     *
     * 一旦整体解析失败，不再直接返回空列表，而是降级到第三层逐对象解析，
     * 尽可能保住能解析的需求条目。
     */
    private fun parseRequirementsJson(
        content: String,
        onProgress: ((String) -> Unit)? = null,
        context: String = "",
    ): List<Requirement> {
        val json = extractJsonArray(content, onProgress, context) ?: return emptyList()

        // 先尝试整体解析（最常见路径，性能最好）
        return try {
            val type = object : TypeToken<List<Requirement>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            log.warn("JSON 整体解析失败 [$context]：${e.message}，降级到逐对象兜底，原始（前500字）：${content.take(500)}")
            // 第三层：整体失败，逐对象兜底
            parseObjectsIndividually(json, onProgress, context)
        }
    }

    /**
     * 从 LLM 输出中提取 JSON 数组部分，并尝试修复截断
     *
     * 改动 1（v5）：使用括号深度计数定位顶层数组的闭合 ]，
     * 避免被 JSON 内部嵌套数组（如 acceptance、keywords 的 ]）误判为完整。
     */
    private fun extractJsonArray(
        content: String,
        onProgress: ((String) -> Unit)? = null,
        context: String = "",
    ): String? {
        val trimmed = content.trim()

        // 定位第一个 [
        val startPos = trimmed.indexOf('[')
        if (startPos < 0) {
            log.warn("未找到 JSON 数组起始 [ [$context]，原始（前500字）：${trimmed.take(500)}")
            onProgress?.invoke("⚠ [$context] 未找到 JSON 数组，已记录到日志")
            return null
        }

        // 按括号深度找匹配的闭合 ]
        var depth = 0
        var inString = false
        var escape = false
        for (i in startPos until trimmed.length) {
            val c = trimmed[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        // 顶层数组闭合，JSON 完整
                        return trimmed.substring(startPos, i + 1)
                    }
                }
            }
        }

        // 遍历完 depth 仍未归零 → 尾部截断，尝试修复
        val partial = trimmed.substring(startPos)
        return repairTruncatedJson(partial, onProgress, context)
    }

    /**
     * 修复截断的 JSON 数组
     *
     * 策略：找到最后一个完整对象的 `}`，在其后补 `]`。
     *
     * 关键 bug 修复（v5）：
     *   旧实现用 lastIndexOf('}') 定位最后一个 }，但 description 字符串值内部可能含 }（如
     *   "function(){ return {}; }"），导致截断位置错误。
     *   改用括号深度计数。但若从 partial 开头（外层 [）算，[ 让 depth=1，内部每个对象的 }
     *   闭合时 depth 只能回到 1，永远到不了 0，于是 lastCompleteEnd 始终是 -1，修复逻辑
     *   形同虚设——这正是 Unterminated object 报错的根因。
     *
     *   解决：跳过外层 [，从数组内部（arrStart + 1）开始扫描，这样每个顶层对象的 } 闭合时
     *   depth 能正确归零，才能正确记录最后一个完整对象的位置。
     */
    private fun repairTruncatedJson(
        partial: String,
        onProgress: ((String) -> Unit)? = null,
        context: String = "",
    ): String {
        val arrStart = partial.indexOf('[')
        if (arrStart < 0) return partial

        // 跳过外层 [，从数组内部开始扫描
        val scanStart = arrStart + 1
        var depth = 0
        var inString = false
        var escape = false
        var lastCompleteEnd = -1  // 最后一个完整顶层对象的 } 位置（相对 partial）

        for (i in scanStart until partial.length) {
            val c = partial[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    // depth 归零 = 一个顶层对象完整闭合
                    if (depth == 0) {
                        lastCompleteEnd = i
                    }
                }
            }
        }

        if (lastCompleteEnd < 0) {
            // 没有任何完整对象，返回空数组
            onProgress?.invoke("⚠ [$context] JSON 截断且无完整对象可保留")
            return "[]"
        }

        val repaired = partial.substring(0, lastCompleteEnd + 1) + "]"
        onProgress?.invoke("ℹ [$context] JSON 尾部截断，已尝试修复（保留到最后一个完整对象）")
        log.info("[$context] JSON 修复：原始长度=${partial.length}，修复后=${repaired.length}")
        return repaired
    }

    /**
     * 逐对象兜底解析（第三层容错）
     *
     * 场景：修复后的 JSON 整体解析仍失败（某个对象字段值含非法字符，如未转义引号）。
     * 这时整体 Gson fromJson 会抛异常，所有条目丢失。
     *
     * 新逻辑：切出每个顶层 {...} 对象单独 fromJson，跳过损坏的，保住能解析的。
     * 同样用括号深度计数定位每个对象的边界，跳过字符串内部括号。
     */
    private fun parseObjectsIndividually(
        json: String,
        onProgress: ((String) -> Unit)? = null,
        context: String = "",
    ): List<Requirement> {
        val arrStart = json.indexOf('[')
        if (arrStart < 0) return emptyList()

        val objects = mutableListOf<String>()
        val scanStart = arrStart + 1  // 跳过外层 [
        var depth = 0
        var inString = false
        var escape = false
        var objStart = -1  // 当前对象起始 { 位置

        for (i in scanStart until json.length) {
            val c = json[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> {
                    if (depth == 0) objStart = i  // 顶层对象开始
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        // 一个顶层对象完整闭合
                        objects.add(json.substring(objStart, i + 1))
                        objStart = -1
                    }
                }
            }
        }

        if (objects.isEmpty()) return emptyList()

        val results = mutableListOf<Requirement>()
        var skipped = 0
        for (obj in objects) {
            try {
                val req = gson.fromJson(obj, Requirement::class.java)
                if (req != null) results.add(req)
            } catch (e: Exception) {
                skipped++
            }
        }

        if (skipped > 0) {
            onProgress?.invoke("⚠ [$context] 逐对象兜底：成功 ${results.size} 条，跳过损坏 $skipped 条")
            log.warn("[$context] parseObjectsIndividually：$skipped/${objects.size} 个对象解析失败被跳过")
        }
        return results
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
