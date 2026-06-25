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
    var title: String = "",
    /** 详细描述 */
    var description: String = "",
    /**
     * 关联词（业务关键词列表）
     *
     * 由 LLM 从需求文档中提取，用于：
     * - 代码扫描时精准匹配实现位置
     * - UI 展示需求与代码的对应关系
     */
    var keywords: List<String> = emptyList(),
    /** 验收标准列表 */
    var acceptance: List<String> = emptyList(),
    /**
     * 来源（改动 7c/v4）：该需求来自文档的哪个板块/子板块
     *
     * 由 parser 填充，用于追溯需求出处，便于人工核对。
     */
    var source: String = "",
    /** 是否启用（用于 UI 勾选过滤） */
    var enabled: Boolean = true,
)

/**
 * 需求文档（一个文档对应一个 JSON）
 *
 * 对应 requirements-Showcase.json 的整体结构。
 */
data class RequirementDocument(
    var module: String,
    var source: String,
    var section: String,
    var total: Int,
    var requirements: List<Requirement>,
)

/**
 * 单条需求的覆盖度结果（scan 阶段的输出）
 *
 * 对应 ai-req.js 中 SCAN_SYSTEM_PROMPT 的输出格式。
 */
data class CoverageResult(
    var id: String,
    /** 是否覆盖 */
    var covered: Boolean,
    /** 置信度：high / medium / low */
    var confidence: String,
    /** 代码中的证据（实现了该需求的代码位置） */
    var evidence: String,
    /** 未覆盖的缺口说明 */
    var gap: String,
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
    var module: String,
    var baseBranch: String,
    var timestamp: Long,
    var results: List<CoverageResult>,
    var summary: Summary,
) {
    data class Summary(
        var total: Int,
        var covered: Int,
        var uncovered: Int,
        var partial: Int,
        var coverageRate: Double,
    )
}

// ============================================================================
// v0.8.0 新增：代码质量扫描数据模型
// ============================================================================

/**
 * 单条代码质量问题（由 QualityScanner 输出）
 *
 * 对应 Prompts.qualitySystemPrompt 中定义的输出格式。
 */
data class QualityIssue(
    /** 严重度：high / medium / low */
    val severity: String,
    /** 问题类别：bug / smell / security / performance */
    val category: String,
    /** 文件路径 */
    val file: String,
    /** 位置提示（方法名 / 行号附近） */
    val lineHint: String,
    /** 问题描述：为什么是问题、会引发什么后果 */
    val description: String,
    /** 修复建议（可执行的方向） */
    val suggestion: String,
) {
    /** 用于 UI 展示的严重度文本 */
    fun severityText(): String = when (severity) {
        "high" -> "🔴 高"
        "medium" -> "🟡 中"
        "low" -> "🟢 低"
        else -> severity
    }

    /** 用于 UI 展示的类别文本 */
    fun categoryText(): String = when (category) {
        "bug" -> "🐞 缺陷"
        "security" -> "🔒 安全"
        "performance" -> "⚡ 性能"
        "smell" -> "💩 异味"
        else -> category
    }
}

/**
 * 代码质量扫描汇总
 */
data class QualitySummary(
    var highCount: Int = 0,
    var mediumCount: Int = 0,
    var lowCount: Int = 0,
) {
    /** 总问题数 */
    fun total(): Int = highCount + mediumCount + lowCount
}

/**
 * 代码质量扫描报告（一次 scan 的完整结果）
 */
data class QualityReport(
    /** 发现的问题列表（按 severity 排序后：high → medium → low） */
    val issues: List<QualityIssue>,
    /** 汇总统计 */
    val summary: QualitySummary,
) {
    companion object {
        /** 空报告（无问题或未启用扫描时使用） */
        fun empty(): QualityReport = QualityReport(emptyList(), QualitySummary())
    }
}
