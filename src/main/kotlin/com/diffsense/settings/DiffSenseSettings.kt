package com.diffsense.settings

import com.diffsense.core.DiffSenseConfig
import com.diffsense.core.Prompts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * AI DiffSense 全局设置（持久化）
 *
 * 存储位置：IDE 的 config 目录下 diffsense-settings.xml
 * 通过 Settings → Tools → AI DiffSense 面板修改。
 *
 * 注意：API Key 可以存在这里（本地加密存储），
 * 但推荐通过环境变量 AI_API_KEY 注入，更安全。
 */
@State(
    name = "com.diffsense.settings.DiffSenseSettings",
    storages = [Storage("diffsense-settings.xml")]
)
class DiffSenseSettings : PersistentStateComponent<DiffSenseSettings.State> {

    data class State(
        /** OpenAI 兼容 API 地址 */
        var baseUrl: String = "https://api.openai.com/v1",
        /** 模型名 */
        var model: String = "claude-sonnet-4-20250514",
        /** API Key */
        var apiKey: String = "",
        /** 严重度阈值：strict / normal / relaxed */
        var severity: String = "normal",
        /** 超时（秒） */
        var timeoutSec: Int = 180,
        /** Pre-commit 拦截开关 */
        var preCommitEnabled: Boolean = false,
        /** Pre-commit 阈值：未覆盖需求数超过此值则拦截 */
        var preCommitMaxUncovered: Int = 0,
        /** 需求拆解并发度（同时调用 LLM 的请求数，默认 3） */
        var parseConcurrency: Int = 3,
        /** 覆盖度扫描并发度（需求分批并发，默认 3；需求少时自动降为单批） */
        var scanConcurrency: Int = 3,
        /** 单次请求最大 token（默认 8192；输出被截断时调大） */
        var maxTokens: Int = 8192,

        // ---- v0.8.0 新增：提示词可配置 ----
        /** 需求拆解系统提示词（默认值 = Prompts.parseSystemPrompt） */
        var parsePrompt: String = Prompts.parseSystemPrompt,
        /** 覆盖度扫描系统提示词（默认值 = Prompts.scanSystemPrompt） */
        var scanPrompt: String = Prompts.scanSystemPrompt,
        /** 代码质量扫描系统提示词（默认值 = Prompts.qualitySystemPrompt） */
        var qualityPrompt: String = Prompts.qualitySystemPrompt,

        // ---- v0.8.0 新增：代码质量扫描开关 ----
        /**
         * 是否启用代码质量扫描（默认 true）
         * Settings 面板和扫描窗口都能切换这个开关，两者读写同一个持久化值。
         */
        var qualityScanEnabled: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    /** 转换为 Core 层使用的配置（合并环境变量） */
    fun toConfig(): DiffSenseConfig {
        val envKey = System.getenv("AI_API_KEY") ?: ""
        val envUrl = System.getenv("AI_BASE_URL") ?: ""
        val envModel = System.getenv("AI_MODEL") ?: ""
        return DiffSenseConfig(
            baseUrl = envUrl.ifBlank { state.baseUrl },
            model = envModel.ifBlank { state.model },
            apiKey = envKey.ifBlank { state.apiKey },
            severity = DiffSenseConfig.Severity.fromValue(state.severity),
            timeoutMs = state.timeoutSec * 1000,
            parseConcurrency = state.parseConcurrency.coerceAtLeast(1),
            scanConcurrency = state.scanConcurrency.coerceAtLeast(1),
            maxTokens = state.maxTokens.coerceAtLeast(1024),
            parsePrompt = state.parsePrompt,
            scanPrompt = state.scanPrompt,
            qualityPrompt = state.qualityPrompt,
            qualityScanEnabled = state.qualityScanEnabled,
        )
    }

    companion object {
        /** 获取单例 */
        fun getInstance(): DiffSenseSettings =
            ApplicationManager.getApplication().getService(DiffSenseSettings::class.java)
    }
}
