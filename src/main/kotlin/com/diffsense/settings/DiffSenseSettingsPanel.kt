package com.diffsense.settings

import com.diffsense.core.DiffSenseConfig
import com.diffsense.core.Prompts
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * Settings 面板：Tools → AI DiffSense
 *
 * 让用户配置 LLM API、严重度阈值、Pre-commit 开关、提示词等。
 * 对应 plugin.xml 中的 applicationConfigurable。
 *
 * v0.8.0 新增：
 * - 三个 prompt 文本框（拆解 / 覆盖度 / 质量扫描），各自带「重置为默认」按钮
 * - 代码质量扫描开关（与扫描窗口共享同一个持久化值）
 */
class DiffSenseSettingsPanel {

    // ---- 基础配置 ----
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
    private val scanConcurrencyField = JBTextField().apply { emptyText.text = "3" }
    private val maxTokensField = JBTextField().apply { emptyText.text = "8192" }

    // ---- v0.8.0 新增：代码质量扫描开关 ----
    private val qualityScanCheck = JBCheckBox("启用代码质量扫描（扫描 diff 中的 bug / 安全 / 性能 / 异味）", true)

    // ---- v0.8.0 新增：提示词编辑区 ----
    private val parsePromptArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val scanPromptArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val qualityPromptArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val parsePromptScroll = JScrollPane(parsePromptArea).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(0, 150)
    }
    private val scanPromptScroll = JScrollPane(scanPromptArea).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(0, 150)
    }
    private val qualityPromptScroll = JScrollPane(qualityPromptArea).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(0, 150)
    }

    /** 组装「提示词 + 重置按钮」的横向布局 */
    private fun labeledPromptRow(
        label: String,
        scrollPane: JScrollPane,
        area: JBTextArea,
        defaultPrompt: String,
    ): JComponent {
        val resetBtn = JButton(object : AbstractAction("重置为默认") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                area.text = defaultPrompt
            }
        })
        val row = JPanel(BorderLayout(8, 0))
        row.add(JBLabel(label), BorderLayout.WEST)
        row.add(scrollPane, BorderLayout.CENTER)
        row.add(resetBtn, BorderLayout.EAST)
        return row
    }

    private val rootPanel: JPanel = FormBuilder.createFormBuilder()
        // ---- 基础配置 ----
        .addLabeledComponent("API Base URL", baseUrlField)
        .addLabeledComponent("Model", modelField)
        .addLabeledComponent("API Key", apiKeyField)
        .addLabeledComponent("严重度阈值", severityCombo)
        .addLabeledComponent("超时（秒）", timeoutField)
        .addComponent(preCommitCheck)
        .addLabeledComponent("Pre-commit 最大未覆盖数", preCommitMaxField)
        .addLabeledComponent("需求拆解并发度", parseConcurrencyField)
        .addLabeledComponent("覆盖度扫描并发度", scanConcurrencyField)
        .addLabeledComponent("最大 token（输出截断时调大）", maxTokensField)
        // ---- 代码质量扫描 ----
        .addSeparator()
        .addComponent(qualityScanCheck)
        // ---- 提示词（可自定义，支持一键重置） ----
        .addSeparator()
        .addTooltip("以下三个提示词均可自定义；点击「重置为默认」恢复出厂值。")
        .addComponent(labeledPromptRow("需求拆解 Prompt", parsePromptScroll, parsePromptArea, Prompts.parseSystemPrompt))
        .addComponent(labeledPromptRow("覆盖度扫描 Prompt", scanPromptScroll, scanPromptArea, Prompts.scanSystemPrompt))
        .addComponent(labeledPromptRow("代码质量 Prompt", qualityPromptScroll, qualityPromptArea, Prompts.qualitySystemPrompt))
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
        scanConcurrencyField.text = s.scanConcurrency.toString()
        maxTokensField.text = s.maxTokens.toString()
        qualityScanCheck.isSelected = s.qualityScanEnabled
        parsePromptArea.text = s.parsePrompt
        scanPromptArea.text = s.scanPrompt
        qualityPromptArea.text = s.qualityPrompt
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
        s.scanConcurrency = scanConcurrencyField.text.trim().toIntOrNull()?.coerceAtLeast(1) ?: 3
        s.maxTokens = maxTokensField.text.trim().toIntOrNull()?.coerceAtLeast(1024) ?: 8192
        s.qualityScanEnabled = qualityScanCheck.isSelected
        s.parsePrompt = parsePromptArea.text
        s.scanPrompt = scanPromptArea.text
        s.qualityPrompt = qualityPromptArea.text
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
        // prompt 为空时回退到默认值
        if (parsePromptArea.text.isBlank()) {
            parsePromptArea.text = Prompts.parseSystemPrompt
        }
        if (scanPromptArea.text.isBlank()) {
            scanPromptArea.text = Prompts.scanSystemPrompt
        }
        if (qualityPromptArea.text.isBlank()) {
            qualityPromptArea.text = Prompts.qualitySystemPrompt
        }
        return true
    }
}
