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
     * 用户可在 Settings → Tools → AI DiffSense 中调整。
     * 默认 3，避免对 API 服务器造成过大压力。
     */
    val parseConcurrency: Int = 3,

    /**
     * 覆盖度扫描阶段的并发度（需求分批并发调用 LLM）
     *
     * v0.9.0 新增：需求较多时按此值分批，每批并发调用 LLM。
     * 默认 3；需求总数 ≤ 此值时自动降为单批，无额外开销。
     */
    val scanConcurrency: Int = 3,

    // ---- v0.8.0 新增：提示词（用户可在 Settings 自定义） ----
    /** 需求拆解系统提示词 */
    val parsePrompt: String = Prompts.parseSystemPrompt,
    /** 覆盖度扫描系统提示词 */
    val scanPrompt: String = Prompts.scanSystemPrompt,
    /** 代码质量扫描系统提示词 */
    val qualityPrompt: String = Prompts.qualitySystemPrompt,

    // ---- v0.8.0 新增：代码质量扫描开关 ----
    /** 是否启用代码质量扫描（Settings 与扫描窗口共享同一持久化值） */
    val qualityScanEnabled: Boolean = true,
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
