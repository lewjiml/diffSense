package com.diffsense.core

/**
 * Token 消耗统计（单例）
 *
 * 按 parse / scan / quality 三个阶段分别统计调用次数和 token 消耗。
 * 对应 ai-req.js 中的 tokenStats。
 */
object TokenStats {

    enum class Stage { PARSE, SCAN, QUALITY }

    data class StageData(
        var calls: Int = 0,
        var promptTokens: Int = 0,
        var completionTokens: Int = 0,
        var totalTokens: Int = 0,
    )

    private val parseData = StageData()
    private val scanData = StageData()
    private val qualityData = StageData()

    /** 记录一次 LLM 调用的 token 用量 */
    fun record(stage: Stage, usage: Usage?) {
        if (usage == null) return
        val target = when (stage) {
            Stage.PARSE -> parseData
            Stage.SCAN -> scanData
            Stage.QUALITY -> qualityData
        }
        target.calls++
        target.promptTokens += usage.promptTokens
        target.completionTokens += usage.completionTokens
        target.totalTokens += usage.totalTokens
    }

    /** 获取指定阶段的数据快照（用于 UI 显示） */
    fun snapshot(stage: Stage): StageData {
        val src = when (stage) {
            Stage.PARSE -> parseData
            Stage.SCAN -> scanData
            Stage.QUALITY -> qualityData
        }
        return src.copy()
    }

    /** 合计 token 数 */
    fun totalTokens(): Int = parseData.totalTokens + scanData.totalTokens + qualityData.totalTokens

    /** 合计调用次数 */
    fun totalCalls(): Int = parseData.calls + scanData.calls + qualityData.calls

    /** 重置所有统计（新一轮分析前调用） */
    fun reset() {
        parseData.calls = 0
        parseData.promptTokens = 0
        parseData.completionTokens = 0
        parseData.totalTokens = 0
        scanData.calls = 0
        scanData.promptTokens = 0
        scanData.completionTokens = 0
        scanData.totalTokens = 0
        qualityData.calls = 0
        qualityData.promptTokens = 0
        qualityData.completionTokens = 0
        qualityData.totalTokens = 0
    }

    /** 生成可读的报告字符串 */
    fun report(): String {
        val bar = "─".repeat(56)
        val sb = StringBuilder()
        sb.append('\n').append(bar).append('\n')
        sb.append("  💰 Token 消耗统计\n")
        sb.append(bar).append('\n')
        sb.append("  ").append("阶段".padEnd(16))
        sb.append("调用次数".padStart(8))
        sb.append("输入token".padStart(11))
        sb.append("输出token".padStart(11))
        sb.append("合计token".padStart(11)).append('\n')
        appendRow(sb, "parse(需求拆解)", parseData)
        appendRow(sb, "scan(覆盖扫描)", scanData)
        appendRow(sb, "quality(质量)", qualityData)
        val total = StageData(
            calls = parseData.calls + scanData.calls + qualityData.calls,
            promptTokens = parseData.promptTokens + scanData.promptTokens + qualityData.promptTokens,
            completionTokens = parseData.completionTokens + scanData.completionTokens + qualityData.completionTokens,
            totalTokens = parseData.totalTokens + scanData.totalTokens + qualityData.totalTokens,
        )
        appendRow(sb, "合计", total)
        sb.append(bar)
        return sb.toString()
    }

    private fun appendRow(sb: StringBuilder, label: String, data: StageData) {
        sb.append("  ")
        sb.append(label.padEnd(16))
        sb.append(data.calls.toString().padStart(8))
        sb.append(data.promptTokens.toString().padStart(11))
        sb.append(data.completionTokens.toString().padStart(11))
        sb.append(data.totalTokens.toString().padStart(11)).append('\n')
    }

    /** LLM 返回的 usage 字段（OpenAI 兼容） */
    data class Usage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
    )
}
