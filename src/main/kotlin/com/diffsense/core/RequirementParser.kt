package com.diffsense.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator

/**
 * 需求拆解器（parse 阶段）
 *
 * 对应 ai-req.js 中的 parseRequirements() 函数。
 *
 * 工作流：
 *   需求文档 MD
 *     → [MarkdownSplitter] 按 ## 切片
 *     → 按用户选择的板块/子板块过滤
 *     → 对每个片段调用 LLM 拆解
 *     → 合并成 RequirementDocument
 *
 * @param config   配置
 * @param indicator 进度指示器（支持取消）
 */
class RequirementParser(
    config: DiffSenseConfig,
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
     * @return 结构化需求文档
     */
    fun parse(
        md: String,
        module: String,
        sectionFilter: List<String>? = null,
        subSectionFilter: List<String>? = null,
    ): RequirementDocument {
        log.info("[parse] start: module=$module, sections=${sectionFilter?.size ?: 0}")

        val sections = MarkdownSplitter.splitByHeading(md)
            .filter { sectionFilter.isNullOrEmpty() || it.first in sectionFilter }

        val allRequirements = mutableListOf<Requirement>()
        var reqCounter = 1

        for ((secTitle, secBody) in sections) {
            indicator?.apply {
                text = "拆解需求：$secTitle"
                checkCanceled()
            }

            // 进一步按 ### 切片
            val subSections = MarkdownSplitter.splitBySubHeading(secBody)
                .filter { subSectionFilter.isNullOrEmpty() || it.first in subSectionFilter }

            for ((subTitle, subBody) in subSections) {
                indicator?.checkCanceled()

                val userMsg = buildUserMessage(secTitle, subTitle, subBody)
                val content = llm.chat(Prompts.parseSystemPrompt, userMsg, indicator)

                val reqs = parseRequirementsJson(content)
                reqs.forEach { r ->
                    r.id = "REQ-${reqCounter.toString().padStart(3, '0')}"
                    reqCounter++
                    allRequirements.add(r)
                }
                log.info("[parse] $secTitle/$subTitle → ${reqs.size} 条")
            }
        }

        return RequirementDocument(
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
     *   - markdown 代码块包裹（```json ... ```）
     *   - 带前后多余文本
     */
    private fun parseRequirementsJson(content: String): List<Requirement> {
        val json = extractJsonArray(content) ?: return emptyList()
        val type = object : TypeToken<List<Requirement>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    /** 从 LLM 输出中提取 JSON 数组部分 */
    private fun extractJsonArray(content: String): String? {
        // 1. 尝试直接解析
        val trimmed = content.trim()
        if (trimmed.startsWith("[")) return trimmed

        // 2. 尝试从 ```json ... ``` 中提取
        val codeBlock = Regex("""```(?:json)?\s*(.+?)\s*```""", RegexOption.DOT_MATCHES_ALL)
            .find(trimmed)
        if (codeBlock != null) {
            val inner = codeBlock.groupValues[1].trim()
            if (inner.startsWith("[")) return inner
        }

        // 3. 尝试找到第一个 [ 到最后一个 ]
        val first = trimmed.indexOf('[')
        val last = trimmed.lastIndexOf(']')
        if (first in 0 until last) {
            return trimmed.substring(first, last + 1)
        }
        return null
    }

    /** 将 RequirementDocument 序列化为 JSON 字符串（用于保存到文件） */
    fun toJson(doc: RequirementDocument): String {
        val type = object : TypeToken<RequirementDocument>() {}.type
        return gson.toJson(doc, type)
    }

    /** 从 JSON 字符串反序列化（用于读取已保存的 requirements.json） */
    fun fromJson(json: String): RequirementDocument {
        val type = object : TypeToken<RequirementDocument>() {}.type
        return gson.fromJson(json, type)
    }
}
