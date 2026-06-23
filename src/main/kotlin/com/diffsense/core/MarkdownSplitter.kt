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
     * 按 `###` 三级标题进一步分块
     *
     * 问题 2 改进：每个 ### 内部如果含大表格（≥3 行），按表格行进一步切分，
     * 避免 12 行表格整块塞给 LLM 导致截断。
     *
     * 若没有 ### 子标题但含表格，同样按表格行切分。
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
            // 问题 2：### 内部如果有大表格，按表格行进一步切分
            val tableSlices = splitByTableRows(body)
            if (tableSlices.size > 1) {
                result.addAll(tableSlices)
            } else {
                result.add(title to body)
            }
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
     * 列出文档中所有 `###` 子板块标题（跨所有 ## 板块）
     *
     * 问题 1 改进：支持按 ### 级别关键词过滤。
     */
    fun listAllSubSections(md: String): List<String> {
        val cleaned = stripStrikethrough(md)
        return HEADING_3.findAll(cleaned).map { it.groupValues[1].trim() }.toList()
    }

    /**
     * 根据关键词过滤，返回需要拆解的 (板块标题, 子板块标题) 列表
     *
     * 问题 1 改进：两层匹配下钻到 ### 级别。
     *
     * 匹配规则：
     *   1. 关键词命中某个 ##（如「2.10」）→ 拆该 ## 下所有 ###（2.10.1 + 2.10.2 + ...）
     *   2. 关键词没命中 ## 但命中某个 ###（如「2.10.1」）→ 只拆那个 ###
     *   3. 空列表 → 全部板块的全部子板块
     *
     * @return 列表元素 = (所在 ## 板块标题, ### 子板块标题)
     */
    fun filterToSubSections(md: String, keywords: List<String>): List<Pair<String, String>> {
        if (keywords.isEmpty()) {
            // 全部：每个 ## 下所有 ###
            return splitByHeading(md).flatMap { (secTitle, secBody) ->
                val subs = listSubSections(secBody)
                if (subs.isEmpty()) listOf(secTitle to secTitle)
                else subs.map { sub -> secTitle to sub }
            }
        }

        val sections = splitByHeading(md)
        val result = mutableListOf<Pair<String, String>>()

        for ((secTitle, secBody) in sections) {
            val secMatched = keywords.any { kw -> secTitle.contains(kw.trim(), ignoreCase = true) }
            val subs = listSubSections(secBody)

            if (secMatched) {
                // 规则 1：关键词命中 ## → 拆该板块下所有 ###
                if (subs.isEmpty()) result.add(secTitle to secTitle)
                else result.addAll(subs.map { sub -> secTitle to sub })
            } else if (subs.isNotEmpty()) {
                // 规则 2：关键词没命中 ##，但可能命中 ### → 只拆命中的 ###
                val matchedSubs = subs.filter { sub ->
                    keywords.any { kw -> sub.contains(kw.trim(), ignoreCase = true) }
                }
                result.addAll(matchedSubs.map { sub -> secTitle to sub })
            }
            // ## 和 ### 都没命中 → 跳过
        }
        return result
    }

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
     * 问题 1 改进：使用 filterToSubSections 的两层匹配逻辑（下钻到 ###）。
     */
    fun countSlices(md: String, sectionKeywords: List<String> = emptyList()): Int {
        val subSecPairs = filterToSubSections(md, sectionKeywords)
        // 每个 (sec, sub) 对应一个或多个实际片段（### 内表格可能再切分）
        var count = 0
        val sections = splitByHeading(md)
        for ((secTitle, subTitle) in subSecPairs) {
            val secBody = sections.firstOrNull { it.first == secTitle }?.second ?: continue
            if (subTitle == secTitle) {
                // 板块无子标题，可能按表格行切分
                val tableSlices = splitByTableRows(secBody)
                count += if (tableSlices.size > 1) tableSlices.size else 1
            } else {
                // 找到该 ### 的内容
                val allSubs = splitBySubHeading(secBody)
                val subBody = allSubs.firstOrNull { it.first == subTitle }?.second
                if (subBody != null) {
                    val tableSlices = splitByTableRows(subBody)
                    count += if (tableSlices.size > 1) tableSlices.size else 1
                } else {
                    count += 1
                }
            }
        }
        return count
    }

    /** 剔除 ~~删除线~~ 噪声（废弃内容）
     *
     * 问题 3c 改进：行级判断。
     *   - 若整行剔除删除线后只剩空白/分隔符（如 `| ** | |`），整行丢弃
     *   - 否则只剔除删除线文本，保留行其余内容（如 `| ~~位点~~ 致病性 |` → `| 致病性 |`）
     */
    fun stripStrikethrough(md: String): String {
        return md.lines().joinToString("\n") { line ->
            val cleaned = STRIKETHROUGH.replace(line) { "" }
            // 判断剔除后是否只剩无意义字符（空白、表格分隔符、markdown 强调符）
            val residue = cleaned
                .replace("|", " ")
                .replace("*", " ")
                .replace("-", " ")
                .replace(":", " ")
                .trim()
            if (residue.isEmpty()) "" else cleaned
        }
    }

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
