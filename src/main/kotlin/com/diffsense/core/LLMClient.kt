package com.diffsense.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 大模型调用客户端（OpenAI 兼容协议）
 *
 * 对应 ai-req.js 中的 callLLM()，使用 JDK 11+ 自带的
 * java.net.http.HttpClient，零额外依赖。
 *
 * 特点：
 * - 支持 ProgressIndicator 检查取消
 * - 自动记录 token 用量到 [TokenStats]
 * - 返回纯文本 content，由上层自行解析 JSON
 *
 * @param config 配置
 * @param stage  当前阶段，用于 token 统计
 */
class LLMClient(
    private val config: DiffSenseConfig,
    private val stage: TokenStats.Stage,
) {

    private val log = logger<LLMClient>()
    private val gson = Gson()

    companion object {
        /**
         * 全局共享 HttpClient（线程安全，JDK 官方推荐复用）
         *
         * 复用 TCP 连接 / TLS 握手，在并发调用（parse / scan 分批）场景下
         * 能显著减少连接建立开销。
         */
        private val SHARED_CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    /**
     * 调用 chat/completions 接口
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @param indicator    进度指示器（可空，用于检查用户是否取消）
     * @return 大模型返回的文本内容
     * @throws Exception 网络/HTTP/取消 等异常
     */
    fun chat(
        systemPrompt: String,
        userMessage: String,
        indicator: ProgressIndicator? = null,
    ): String {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"

        val payload = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage),
            )))
            // 改动 7a（v4）：temperature 降为 0，保证拆解结果稳定性
            addProperty("temperature", 0.0)
            addProperty("max_tokens", config.maxTokens)
        }

        val body = gson.toJson(payload)
        log.info("[$stage] POST $url (model=${config.model}, ${body.length}B)")

        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(config.timeoutMs.toLong()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val client = SHARED_CLIENT

        // 同步发送，但通过 indicator 支持取消
        indicator?.checkCanceled()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        indicator?.checkCanceled()

        if (resp.statusCode() !in 200..299) {
            val err = "LLM API 失败 [${resp.statusCode()}]: ${resp.body().take(500)}"
            log.warn(err)
            throw RuntimeException(err)
        }

        val root = JsonParser.parseString(resp.body()).asJsonObject
        val content = root.getAsJsonArray("choices")
            ?.get(0)?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")?.asString
            ?: throw RuntimeException("LLM 返回缺少 choices.message.content")

        // 记录 token 消耗
        val usage = root.getAsJsonObject("usage")
        if (usage != null) {
            val u = TokenStats.Usage(
                promptTokens = usage.get("prompt_tokens")?.asInt ?: 0,
                completionTokens = usage.get("completion_tokens")?.asInt ?: 0,
                totalTokens = usage.get("total_tokens")?.asInt ?: 0,
            )
            TokenStats.record(stage, u)
            log.info("[$stage] token: prompt=${u.promptTokens}, completion=${u.completionTokens}, total=${u.totalTokens}")
        }

        return content
    }
}
