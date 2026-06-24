package com.diffsense.core

/**
 * DiffSense 全局配置（运行时只读快照）
 *
 * 对应 ai-req.js 中的 BASE_URL / API_KEY / MODEL 三个常量，
 * 以及 .aireview.yml 中的 modules 映射、severity 阈值。
 *
 * 实际值由 [com.diffsense.settings.DiffSenseSettings] 注入，
 * 支持来源优先级：项目 .aireview.yml > IDE Settings > 默认值。
 */
data class DiffSenseConfig(
    /** OpenAI 兼容 API 地址（末尾不带斜杠） */
    val baseUrl: String = "https://api.openai.com/v1",

    /** 模型名（支持 claude-sonnet-4-20250514 等） */
    val model: String = "claude-sonnet-4-20250514",

    /** API Key（从环境变量或 Settings 读取，不落库） */
    val apiKey: String = "",

    /** 模块 → 代码路径映射 */
    val modules: Map<String, List<String>> = emptyMap(),

    /** 严重度阈值：strict / normal / relaxed */
    val severity: Severity = Severity.NORMAL,

    /** LLM 调用超时（毫秒） */
    val timeoutMs: Int = 180_000,

    /** 单次请求最大 token（8192 适合大多数主流模型；如遇截断可在 Settings 调大） */
    val maxTokens: Int = 8192,

    /**
     * 需求拆解阶段的并发度（同时调用 LLM 的请求数）
     *
     * 用户可在 Settings → Tools → DiffSense 中调整。
     * 默认 3，避免对 API 服务器造成过大压力。
     */
    val parseConcurrency: Int = 3,
) {
    enum class Severity(val label: String, val value: String) {
        STRICT("严格（致命+严重）", "strict"),
        NORMAL("一般（致命+严重+一般）", "normal"),
        RELAXED("宽松（全部）", "relaxed");

        companion object {
            fun fromValue(v: String?): Severity =
                values().firstOrNull { it.value == v } ?: NORMAL
        }
    }
}
