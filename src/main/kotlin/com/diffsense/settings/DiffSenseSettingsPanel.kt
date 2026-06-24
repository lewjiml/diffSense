package com.diffsense.settings

import com.diffsense.core.DiffSenseConfig
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings 面板：Tools → DiffSense
 *
 * 让用户配置 LLM API、严重度阈值、Pre-commit 开关等。
 * 对应 plugin.xml 中的 applicationConfigurable。
 */
class DiffSenseSettingsPanel {

    private val baseUrlField = JBTextField().apply {
        emptyText.text = "https://api.openai.com/v1"
    }
    private val modelField = JBTextField().apply {
        emptyText.text = "claude-sonnet-4-20250514"
    }
    private val apiKeyField = JBTextField().apply {
        emptyText.text = "留空则从环境变量 AI_API_KEY 读取"
    }
    private val severityCombo = JComboBox(DefaultComboBoxModel(
        arrayOf("strict", "normal", "relaxed")
    ))
    private val timeoutField = JBTextField().apply { emptyText.text = "180" }
    private val preCommitCheck = JBCheckBox("启用 Pre-commit 拦截", false)
    private val preCommitMaxField = JBTextField().apply { emptyText.text = "0" }
    private val parseConcurrencyField = JBTextField().apply { emptyText.text = "3" }
    private val maxTokensField = JBTextField().apply { emptyText.text = "8192" }

    private val rootPanel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("API Base URL", baseUrlField)
        .addLabeledComponent("Model", modelField)
        .addLabeledComponent("API Key", apiKeyField)
        .addLabeledComponent("严重度阈值", severityCombo)
        .addLabeledComponent("超时（秒）", timeoutField)
        .addComponent(preCommitCheck)
        .addLabeledComponent("Pre-commit 最大未覆盖数", preCommitMaxField)
        .addLabeledComponent("需求拆解并发度", parseConcurrencyField)
        .addLabeledComponent("最大 token（输出截断时调大）", maxTokensField)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getRoot(): JComponent = rootPanel

    /** 从 DiffSenseSettings 加载到 UI */
    fun load(settings: DiffSenseSettings) {
        val s = settings.state
        baseUrlField.text = s.baseUrl
        modelField.text = s.model
        apiKeyField.text = s.apiKey
        severityCombo.selectedItem = s.severity
        timeoutField.text = s.timeoutSec.toString()
        preCommitCheck.isSelected = s.preCommitEnabled
        preCommitMaxField.text = s.preCommitMaxUncovered.toString()
        parseConcurrencyField.text = s.parseConcurrency.toString()
        maxTokensField.text = s.maxTokens.toString()
    }

    /** 从 UI 写回 DiffSenseSettings */
    fun save(settings: DiffSenseSettings): Boolean {
        val s = settings.state
        s.baseUrl = baseUrlField.text.trim()
        s.model = modelField.text.trim()
        s.apiKey = apiKeyField.text.trim()
        s.severity = (severityCombo.selectedItem as? String) ?: "normal"
        s.timeoutSec = timeoutField.text.trim().toIntOrNull() ?: 180
        s.preCommitEnabled = preCommitCheck.isSelected
        s.preCommitMaxUncovered = preCommitMaxField.text.trim().toIntOrNull() ?: 0
        s.parseConcurrency = parseConcurrencyField.text.trim().toIntOrNull()?.coerceAtLeast(1) ?: 3
        s.maxTokens = maxTokensField.text.trim().toIntOrNull()?.coerceAtLeast(1024) ?: 8192
        return true
    }

    /** 校验输入 */
    fun validateInput(): Boolean {
        if (baseUrlField.text.isBlank()) {
            baseUrlField.text = "https://api.openai.com/v1"
        }
        if (modelField.text.isBlank()) {
            modelField.text = "claude-sonnet-4-20250514"
        }
        return true
    }
}
