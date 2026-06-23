package com.diffsense.core

/**
 * Markdown 需求文档切分器
 *
 * 对应 ai-req.js 中的：
 *   - splitByHeading()    按 ## 二级标题切片
 *   - splitBySubHeading() 按 ### 三级标题分块
 *   - listSections()      列出所有板块
 *
 * 切分规则：
 *   - 一级标题（#）视为文档标题，不参与切分
 *   - 二级标题（##）作为"板块"（section）的边界
 *   - 三级标题（###）作为"子板块"（sub-section）的边界
 *   - 表格型章节（无 ### 子标题但含多行表格）按表格行进一步切分
 */
object MarkdownSplitter {

    private val HEADING_2 = Regex("""(?m)^##\s+(.+)$""")
    private val HEADING_3 = Regex("""(?m)^###\s+(.+)$""")
    private val TABLE_ROW = Regex("""(?m)^\|.*\|$""")
    private val STRIKETHROUGH = Regex("""~~[^~]+~~""")

    /**
     * 按 `##` 二级标题切片
     *
     * @param md markdown 原文（会先剔除 ~~删除线~~ 噪声）
     * @return 有序列表，元素 = (板块标题, 板块内容)
     */
    fun splitByHeading(md: String): List<Pair<String, String>> {
        val cleaned = stripStrikethrough(md)
        val result = mutableListOf<Pair<String, String>>()
        val matches = HEADING_2.findAll(cleaned).toList()
        if (matches.isEmpty()) {
            val title = extractDocTitle(cleaned) ?: "全部"
            return listOf(title to cleaned)
        }
        matches.forEachIndexed { i, m ->
            val title = m.groupValues[1].trim()
            val start = m.range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else cleaned.length
            val body = cleaned.substring(start, end).trim()
            result.add(title to body)
        }
        return result
    }

    /**
     * 按 `###` 三级标题进一步分块；若没有子标题但含表格，则按表格行切分
     *
     * @param sectionBody 单个板块的内容（来自 [splitByHeading] 的 body）
     * @return (子板块标题, 子板块内容) 列表
     */
    fun splitBySubHeading(sectionBody: String): List<Pair<String, String>> {
        val matches = HEADING_3.findAll(sectionBody).toList()
        if (matches.isEmpty()) {
            // 没有 ### 子标题，尝试按表格行切分
            val tableSlices = splitByTableRows(sectionBody)
            if (tableSlices.size > 1) return tableSlices
            return listOf(extractLeadingHeading(sectionBody) to sectionBody)
        }
        val result = mutableListOf<Pair<String, String>>()
        matches.forEachIndexed { i, m ->
            val title = m.groupValues[1].trim()
            val start = m.range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else sectionBody.length
            val body = sectionBody.substring(start, end).trim()
            result.add(title to body)
        }
        return result
    }

    /**
     * 按表格行切分（用于无 ### 子标题的表格型章节）
     *
     * 只在表格行数 >= 3（含表头+分隔行+至少1数据行）时切分；
     * 每个数据行连同表头组成一个片段，便于 LLM 逐行拆解。
     */
    private fun splitByTableRows(body: String): List<Pair<String, String>> {
        val lines = body.lines()
        val tableLineIndices = lines.mapIndexedNotNull { i, line ->
            if (TABLE_ROW.matches(line.trim())) i else null
        }
        // 表格行少于 3 行（只有表头+分隔行或更少）不切分
        if (tableLineIndices.size < 3) return emptyList()

        // 找到表格前的标题/引导文本（作为前缀）
        val firstTableIdx = tableLineIndices.first()
        val lastTableIdx = tableLineIndices.last()
        // 确认这些行是连续的表格块
        val consecutive = (firstTableIdx..lastTableIdx).all { it in tableLineIndices }
        if (!consecutive) return emptyList()

        val headerLines = lines.subList(firstTableIdx, firstTableIdx + 2) // 表头+分隔行
        val prefix = if (firstTableIdx > 0) lines.subList(0, firstTableIdx).joinToString("\n") else ""

        val result = mutableListOf<Pair<String, String>>()
        for (dataIdx in firstTableIdx + 2..lastTableIdx) {
            val row = lines[dataIdx]
            val firstCell = row.split("|").getOrNull(1)?.trim()?.isNotEmpty() == true
            if (!firstCell) continue
            val cellText = row.split("|").filter { it.isNotBlank() }
                .joinToString(" ") { it.trim() }
            val title = cellText.take(40)
            val slice = buildString {
                if (prefix.isNotBlank()) {
                    appendLine(prefix)
                    appendLine()
                }
                headerLines.forEach { appendLine(it) }
                appendLine(row)
            }
            result.add(title to slice)
        }
        return result
    }

    /** 列出文档中所有 `##` 板块标题 */
    fun listSections(md: String): List<String> =
        HEADING_2.findAll(stripStrikethrough(md)).map { it.groupValues[1].trim() }.toList()

    /** 列出指定板块下的所有 `###` 子板块标题 */
    fun listSubSections(sectionBody: String): List<String> =
        HEADING_3.findAll(sectionBody).map { it.groupValues[1].trim() }.toList()

    /**
     * 根据板块过滤关键词，返回匹配的板块标题列表
     *
     * 匹配规则：包含匹配（关键词是标题的子串即命中）。
     * 空列表返回全部。
     */
    fun filterSections(md: String, keywords: List<String>): List<String> {
        if (keywords.isEmpty()) return listSections(md)
        val all = listSections(md)
        return all.filter { title -> keywords.any { kw -> title.contains(kw.trim(), ignoreCase = true) } }
    }

    /**
     * 预览将拆解出多少个片段（不调用 LLM）
     *
     * 用于「拆解前预览片段数」功能，让用户预估成本。
     */
    fun countSlices(md: String, sectionKeywords: List<String> = emptyList()): Int {
        val sections = splitByHeading(md)
            .filter { sec ->
                if (sectionKeywords.isEmpty()) true
                else sectionKeywords.any { kw -> sec.first.contains(kw.trim(), ignoreCase = true) }
            }
        var count = 0
        for ((_, body) in sections) {
            val subs = splitBySubHeading(body)
            count += subs.size
        }
        return count
    }

    /** 剔除 ~~删除线~~ 噪声（废弃内容） */
    fun stripStrikethrough(md: String): String =
        STRIKETHROUGH.replace(md) { "" }

    /** 提取文档的一级标题（# 标题），没有则返回 null */
    private fun extractDocTitle(md: String): String? {
        val m = Regex("""(?m)^#\s+(.+)$""").find(md) ?: return null
        return m.groupValues[1].trim()
    }

    /** 提取内容开头的标题（用于没有子标题时给一个默认名） */
    private fun extractLeadingHeading(body: String): String {
        val m2 = HEADING_2.find(body)
        if (m2 != null) return m2.groupValues[1].trim()
        val m3 = HEADING_3.find(body)
        if (m3 != null) return m3.groupValues[1].trim()
        return "全部"
    }
}
