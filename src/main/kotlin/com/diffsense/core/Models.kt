package com.diffsense.core

/**
 * 结构化需求条目（parse 阶段的输出）
 *
 * 对应 ai-req.js 中 PARSE_SYSTEM_PROMPT 的输出格式，
 * 以及 requirements-Showcase.json 中的 requirement 对象。
 *
 * JSON 字段名和 JS 版完全一致，保证数据互通。
 */
data class Requirement(
    /** 需求 ID，由 parser 填充（REQ-001） */
    var id: String = "",
    /** 需求标题（简短） */
    val title: String,
    /** 详细描述 */
    val description: String,
    /** 优先级：P0 / P1 / P2 / P3 */
    val priority: String = "P1",
    /** 分类：功能 / 性能 / 安全 / 兼容性 / 用户体验 */
    val category: String = "功能",
    /** 验收标准列表 */
    val acceptance: List<String> = emptyList(),
    /** 是否启用（用于 UI 勾选过滤） */
    var enabled: Boolean = true,
)

/**
 * 需求文档（一个文档对应一个 JSON）
 *
 * 对应 requirements-Showcase.json 的整体结构。
 */
data class RequirementDocument(
    val module: String,
    val source: String,
    val section: String,
    val total: Int,
    val requirements: List<Requirement>,
)

/**
 * 单条需求的覆盖度结果（scan 阶段的输出）
 *
 * 对应 ai-req.js 中 SCAN_SYSTEM_PROMPT 的输出格式。
 */
data class CoverageResult(
    val id: String,
    /** 是否覆盖 */
    val covered: Boolean,
    /** 置信度：high / medium / low */
    val confidence: String,
    /** 代码中的证据（实现了该需求的代码位置） */
    val evidence: String,
    /** 未覆盖的缺口说明 */
    val gap: String,
) {
    /** 用于 UI 展示的状态文本 */
    fun statusText(): String = when {
        !covered -> "❌ 未覆盖"
        confidence == "high" -> "✅ 高"
        confidence == "medium" -> "⚠️ 中"
        else -> "⚠️ 低"
    }
}

/**
 * 扫描报告（一次 scan 的完整结果）
 */
data class ScanReport(
    val module: String,
    val baseBranch: String,
    val timestamp: Long,
    val results: List<CoverageResult>,
    val summary: Summary,
) {
    data class Summary(
        val total: Int,
        val covered: Int,
        val uncovered: Int,
        val partial: Int,
        val coverageRate: Double,
    )
}
