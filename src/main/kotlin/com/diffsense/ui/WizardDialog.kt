package com.diffsense.ui

import com.diffsense.core.*
import com.diffsense.icons.DiffSenseIcons
import com.diffsense.settings.DiffSenseSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * 三步向导弹窗
 *
 * 交互流程：
 *   Step 1 选模式：Parse Only / Scan Only / 一键 Run
 *   Step 2 填参数：需求文档、模块、子板块、基线分支
 *   Step 3 执行 + 展示：进度条 + 结果表格 + Token 统计
 *
 * 对应 ai-req.js 中三个子命令（parse / scan / run）的统一入口。
 */
class WizardDialog(
    private val project: Project,
) : DialogWrapper(project) {

    private val log = logger<WizardDialog>()

    // ==================== 模式选择 ====================
    private enum class Mode(val label: String, val desc: String) {
        PARSE("分割需求", "从需求文档拆解成结构化需求 JSON"),
        SCAN("扫描代码", "基于已有需求 JSON 判断代码覆盖度"),
        RUN("一键 Run", "分割 + 扫描一条龙"),
    }

    private var mode: Mode = Mode.RUN
    private val modeGroup = ButtonGroup()

    // ==================== 输入字段 ====================
    private val reqDocField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "选择需求文档", "Markdown 格式的需求文档", project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("md")
        )
    }
    private val reqJsonField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "选择需求 JSON", "parse 阶段输出的 requirements.json", project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        )
    }
    private val moduleField = JBTextField("default")
    private val baseBranchField = JBTextField("develop")

    // ==================== 输出区 ====================
    private val resultTable = CoverageResultTable()
    private val tokenLabel = JBLabel("💰 Token：parse 0 / scan 0")
    private val summaryLabel = JBLabel("")

    // ==================== 运行状态 ====================
    private var lastDocument: RequirementDocument? = null
    private var lastReport: ScanReport? = null

    init {
        title = "DiffSense · AI 需求覆盖度审查"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = EmptyBorder(JBUI.insets(8))
        panel.preferredSize = java.awt.Dimension(900, 600)

        panel.add(buildTopPanel(), BorderLayout.NORTH)
        panel.add(buildInputPanel(), BorderLayout.CENTER)
        panel.add(buildBottomPanel(), BorderLayout.SOUTH)
        return panel
    }

    /** 顶部：模式选择 */
    private fun buildTopPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 4))
        Mode.values().forEach { m ->
            val rb = JRadioButton(m.label).apply {
                toolTipText = m.desc
                isSelected = this@WizardDialog.mode == m
                addActionListener { this@WizardDialog.mode = m; updateFieldVisibility() }
            }
            modeGroup.add(rb)
            panel.add(rb)
        }
        panel.add(Box.createHorizontalStrut(16))
        panel.add(JBLabel("Mode").apply { foreground = JBColor.gray })
        return panel
    }

    /** 中部：参数输入 + 结果表格 */
    private fun buildInputPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("需求文档（.md）", reqDocField)
            .addLabeledComponent("或 需求 JSON（scan 用）", reqJsonField)
            .addLabeledComponent("模块名", moduleField)
            .addLabeledComponent("基线分支", baseBranchField)
            .addVerticalGap(8)
            .addComponent(JBLabel("覆盖度结果：").apply { border = EmptyBorder(JBUI.insets(4, 0)) })
            .addComponent(JBScrollPane(resultTable.getComponent()).apply {
                preferredSize = java.awt.Dimension(850, 300)
            })
            .panel
        return form
    }

    /** 底部：执行按钮 + Token 统计 */
    private fun buildBottomPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val left = JPanel(FlowLayout(FlowLayout.LEFT))
        left.add(tokenLabel)
        left.add(summaryLabel)
        panel.add(left, BorderLayout.CENTER)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT))
        val runBtn = JButton("▶ 开始分析", DiffSenseIcons.RUN)
        runBtn.addActionListener { startAnalysis() }
        right.add(runBtn)

        val exportBtn = JButton("导出报告")
        exportBtn.addActionListener { exportReport() }
        right.add(exportBtn)
        panel.add(right, BorderLayout.EAST)
        return panel
    }

    /** 根据模式更新字段可见性 */
    private fun updateFieldVisibility() {
        reqJsonField.isVisible = mode == Mode.SCAN
    }

    /** 开始分析（异步） */
    private fun startAnalysis() {
        val settings = DiffSenseSettings.getInstance()
        val config = settings.toConfig()

        if (config.apiKey.isBlank()) {
            Messages.showWarningDialog(
                project,
                "请先在 Settings → Tools → DiffSense 中配置 API Key",
                "缺少 API Key"
            )
            return
        }

        // 重置 token 统计
        TokenStats.reset()
        resultTable.clear()
        summaryLabel.text = ""

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DiffSense 分析中", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    when (mode) {
                        Mode.PARSE -> doParse(indicator)
                        Mode.SCAN -> doScan(indicator)
                        Mode.RUN -> {
                            doParse(indicator)
                            doScan(indicator)
                        }
                    }
                    refreshUI()
                } catch (e: Exception) {
                    log.warn("分析失败: ${e.message}")
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, e.message ?: "未知错误", "分析失败")
                    }
                }
            }
        })
    }

    /** parse 阶段 */
    private fun doParse(indicator: ProgressIndicator) {
        val mdPath = reqDocField.text.trim()
        if (mdPath.isBlank()) throw RuntimeException("请先选择需求文档（.md）")
        val md = File(mdPath).readText(Charsets.UTF_8)

        val parser = RequirementParser(DiffSenseSettings.getInstance().toConfig(), indicator)
        val doc = parser.parse(
            md = md,
            module = moduleField.text.trim().ifBlank { "default" },
        )
        lastDocument = doc

        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(
                project,
                "需求拆解完成：共 ${doc.total} 条需求",
                "Parse 完成"
            )
        }
    }

    /** scan 阶段 */
    private fun doScan(indicator: ProgressIndicator) {
        val doc = lastDocument ?: loadReqJson() ?: throw RuntimeException("请先 parse 或选择 requirements.json")
        val diff = DiffCollector.collectDiff(project, baseBranchField.text.trim())
        if (diff.isBlank()) throw RuntimeException("未收集到代码改动（diff 为空）")

        val scanner = CoverageScanner(DiffSenseSettings.getInstance().toConfig(), indicator)
        val report = scanner.scan(
            requirements = doc.requirements.filter { it.enabled },
            diff = diff,
            module = moduleField.text.trim().ifBlank { "default" },
            baseBranch = baseBranchField.text.trim(),
        )
        lastReport = report
    }

    /** 从 reqJsonField 读取已存在的 requirements.json */
    private fun loadReqJson(): RequirementDocument? {
        val jsonPath = reqJsonField.text.trim()
        if (jsonPath.isBlank() || !File(jsonPath).exists()) return null
        val json = File(jsonPath).readText(Charsets.UTF_8)
        val parser = RequirementParser(DiffSenseSettings.getInstance().toConfig())
        return parser.fromJson(json)
    }

    /** 回到 EDT 刷新结果 UI */
    private fun refreshUI() {
        ApplicationManager.getApplication().invokeLater {
            val doc = lastDocument
            val report = lastReport
            if (doc != null && report != null) {
                resultTable.showReport(report, doc.requirements)
                val s = report.summary
                summaryLabel.text = "覆盖 ${s.covered}/${s.total}（${(s.coverageRate * 100).toInt()}%）"
            }
            tokenLabel.text = "💰 Token：parse ${TokenStats.snapshot(TokenStats.Stage.PARSE).totalTokens} / scan ${TokenStats.snapshot(TokenStats.Stage.SCAN).totalTokens}"
        }
    }

    /** 导出报告（MD 格式） */
    private fun exportReport() {
        val report = lastReport ?: run {
            Messages.showInfoMessage(project, "请先执行扫描", "无报告")
            return
        }
        val file = File(project.basePath, "coverage-report-${System.currentTimeMillis() / 1000}.md")
        file.writeText(renderMarkdown(report), Charsets.UTF_8)
        Messages.showInfoMessage(project, "报告已导出：${file.absolutePath}", "导出成功")
    }

    /** 把报告渲染为 Markdown */
    private fun renderMarkdown(report: ScanReport): String {
        val sb = StringBuilder()
        sb.appendLine("# DiffSense 覆盖度报告")
        sb.appendLine()
        sb.appendLine("- 模块：${report.module}")
        sb.appendLine("- 基线：${report.baseBranch}")
        sb.appendLine("- 时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(report.timestamp))}")
        sb.appendLine("- 覆盖率：${report.summary.covered}/${report.summary.total}（${(report.summary.coverageRate * 100).toInt()}%）")
        sb.appendLine()
        sb.appendLine("## 明细")
        sb.appendLine()
        sb.appendLine("| ID | 状态 | 置信度 | 证据 / 缺口 |")
        sb.appendLine("|----|------|--------|------------|")
        report.results.forEach { r ->
            sb.appendLine("| ${r.id} | ${r.statusText()} | ${r.confidence} | ${r.evidence.ifBlank { r.gap }} |")
        }
        sb.appendLine()
        sb.appendLine("```")
        sb.appendLine(TokenStats.report())
        sb.appendLine("```")
        return sb.toString()
    }
}
