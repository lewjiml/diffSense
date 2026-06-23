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
 */
object MarkdownSplitter {

    private val HEADING_2 = Regex("""(?m)^##\s+(.+)$""")
    private val HEADING_3 = Regex("""(?m)^###\s+(.+)$""")

    /**
     * 按 `##` 二级标题切片
     *
     * @param md markdown 原文
     * @return 有序列表，元素 = (板块标题, 板块内容)
     *
     * 示例：
     *   # 需求文档
     *   ## 登录
     *   ...
     *   ## 下单
     *   ...
     *   → [("登录", "..."), ("下单", "...")]
     */
    fun splitByHeading(md: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val matches = HEADING_2.findAll(md).toList()
        if (matches.isEmpty()) {
            // 没有二级标题，整个文档作为一个板块
            val title = extractDocTitle(md) ?: "全部"
            return listOf(title to md)
        }
        matches.forEachIndexed { i, m ->
            val title = m.groupValues[1].trim()
            val start = m.range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else md.length
            val body = md.substring(start, end).trim()
            result.add(title to body)
        }
        return result
    }

    /**
     * 按 `###` 三级标题进一步分块
     *
     * @param sectionBody 单个板块的内容（来自 [splitByHeading] 的 body）
     * @return (子板块标题, 子板块内容) 列表；若没有三级标题，返回整体
     */
    fun splitBySubHeading(sectionBody: String): List<Pair<String, String>> {
        val matches = HEADING_3.findAll(sectionBody).toList()
        if (matches.isEmpty()) {
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

    /** 列出文档中所有 `##` 板块标题 */
    fun listSections(md: String): List<String> =
        HEADING_2.findAll(md).map { it.groupValues[1].trim() }.toList()

    /** 列出指定板块下的所有 `###` 子板块标题 */
    fun listSubSections(sectionBody: String): List<String> =
        HEADING_3.findAll(sectionBody).map { it.groupValues[1].trim() }.toList()

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
