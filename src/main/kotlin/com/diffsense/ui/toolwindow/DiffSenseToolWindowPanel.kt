package com.diffsense.ui.toolwindow

import com.diffsense.core.*
import com.diffsense.icons.DiffSenseIcons
import com.diffsense.settings.DiffSenseSettings
import com.diffsense.ui.CoverageResultTable
import com.diffsense.ui.RequirementTable
import com.diffsense.ui.ScanLogPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * DiffSense Tool Window 内容面板（三 Tab）
 *
 * 三个 Tab：
 *   1. 需求 Tab —— 选 MD 文档 → 拆解需求 → 可编辑需求表格
 *   2. 扫描 Tab —— 选需求 JSON + 基线分支 → 扫描代码 → 可编辑结果表格
 *   3. 日志 Tab —— 实时输出每步进度（parse/scan 过程可见）
 *
 * 对应用户反馈：
 *   - 第 1 条：改为边栏窗口，不影响 IDEA 使用
 *   - 第 4 条：扫描结果可编辑
 *   - 第 5 条：扫描过程可见
 */
class DiffSenseToolWindowPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    private val log = logger<DiffSenseToolWindowPanel>()

    // ==================== 共享状态 ====================
    /** 最近一次拆解得到的需求文档（供扫描 Tab 使用） */
    @Volatile
    internal var lastDocument: RequirementDocument? = null

    /** 最近一次扫描报告 */
    @Volatile
    internal var lastReport: ScanReport? = null

    // ==================== 需求 Tab 组件 ====================
    private val reqDocField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "选择需求文档", "Markdown 格式的需求文档", project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("md")
        )
    }
    private val moduleField = JBTextField("default")
    private val requirementTable: RequirementTable = RequirementTable(onEdited = ::onRequirementEdited)

    // ==================== 扫描 Tab 组件 ====================
    private val reqJsonField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "选择需求 JSON", "parse 阶段输出的 requirements.json", project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        )
    }
    private val baseBranchField = JBTextField("develop")
    private val resultTable = CoverageResultTable(onEdited = {
        // 编辑后同步回 lastReport
        lastReport?.let { report ->
            report.summary = rebuildSummary(report.results)
        }
    })
    private val summaryLabel = JBLabel("暂无扫描结果")

    // ==================== 日志 Tab 组件 ====================
    private val logPanel = ScanLogPanel()

    // ==================== 底部状态栏 ====================
    private val tokenLabel = JBLabel("💰 Token：parse 0 / scan 0")

    init {
        val tabs = JBTabbedPane().apply {
            tabPlacement = JTabbedPane.TOP
        }
        tabs.addTab("需求", DiffSenseIcons.PARSE, buildRequirementTab())
        tabs.addTab("扫描", DiffSenseIcons.SCAN, buildScanTab())
        tabs.addTab("日志", DiffSenseIcons.TOKEN, logPanel.getComponent())

        add(tabs, BorderLayout.CENTER)
        add(buildStatusBar(), BorderLayout.SOUTH)
    }

    // ==================== 需求 Tab ====================
    private fun buildRequirementTab(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("需求文档（.md）", reqDocField)
            .addLabeledComponent("模块名", moduleField)
            .addVerticalGap(4)
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("▶ 拆解需求", DiffSenseIcons.PARSE).apply {
                    addActionListener { startParse() }
                })
                add(JButton("💾 保存为 JSON").apply {
                    addActionListener { saveRequirementsJson() }
                })
            })
            .addComponent(JBLabel("需求列表（双击单元格可编辑，关联词用顿号分隔）：").apply {
                border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
                foreground = JBColor.gray
            })
            .addComponent(JBScrollPane(requirementTable.getComponent()).apply {
                preferredSize = java.awt.Dimension(900, 400)
            })
            .panel

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(form, BorderLayout.CENTER)
        }
    }

    // ==================== 扫描 Tab ====================
    private fun buildScanTab(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                "需求 JSON",
                JPanel(BorderLayout()).apply {
                    add(reqJsonField, BorderLayout.CENTER)
                    add(JButton("使用上次拆解").apply {
                        margin = java.awt.Insets(2, 4, 2, 4)
                        toolTipText = "使用需求 Tab 最近一次拆解出的需求"
                        addActionListener { useLastDocument() }
                    }, BorderLayout.EAST)
                }
            )
            .addLabeledComponent("基线分支", baseBranchField)
            .addVerticalGap(4)
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("▶ 开始扫描", DiffSenseIcons.SCAN).apply {
                    addActionListener { startScan() }
                })
                add(JButton("导出报告").apply {
                    addActionListener { exportReport() }
                })
            })
            .addComponent(summaryLabel.apply {
                border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
                foreground = JBColor.gray
            })
            .addComponent(JBScrollPane(resultTable.getComponent()).apply {
                preferredSize = java.awt.Dimension(900, 400)
            })
            .panel

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(form, BorderLayout.CENTER)
        }
    }

    // ==================== 状态栏 ====================
    private fun buildStatusBar(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
            add(tokenLabel)
        }
    }

    /** 需求表格编辑后回调：同步回 lastDocument */
    private fun onRequirementEdited() {
        lastDocument?.let { doc ->
            doc.requirements = requirementTable.getRequirements()
            doc.total = doc.requirements.size
        }
    }

    // ==================== 需求 Tab 动作 ====================
    private fun startParse() {
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

        val mdPath = reqDocField.text.trim()
        if (mdPath.isBlank()) {
            Messages.showWarningDialog(project, "请先选择需求文档（.md）", "缺少文件")
            return
        }
        val mdFile = File(mdPath)
        if (!mdFile.exists()) {
            Messages.showWarningDialog(project, "文件不存在：$mdPath", "错误")
            return
        }

        TokenStats.reset()
        requirementTable.clear()
        logPanel.clear()

        val module = moduleField.text.trim().ifBlank { "default" }
        val md = mdFile.readText(Charsets.UTF_8)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DiffSense 拆解需求", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logPanel.appendLine("开始拆解需求：$mdFile.name，模块=$module")
                    val parser = RequirementParser(config, indicator)
                    val doc = parser.parse(
                        md = md,
                        module = module,
                        onProgress = { line -> logPanel.appendLine(line) },
                    )
                    lastDocument = doc
                    logPanel.appendLine(TokenStats.report())

                    ApplicationManager.getApplication().invokeLater {
                        requirementTable.showRequirements(doc.requirements)
                        refreshToken()
                        Messages.showInfoMessage(
                            project,
                            "需求拆解完成：共 ${doc.total} 条需求",
                            "完成"
                        )
                    }
                } catch (e: Exception) {
                    log.warn("拆解失败: ${e.message}")
                    logPanel.appendLine("✗ 拆解失败：${e.message}")
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, e.message ?: "未知错误", "拆解失败")
                    }
                }
            }
        })
    }

    /** 把当前需求列表导出为 JSON 文件 */
    private fun saveRequirementsJson() {
        val doc = lastDocument
        if (doc == null || doc.requirements.isEmpty()) {
            Messages.showInfoMessage(project, "请先拆解需求", "无数据")
            return
        }
        // 同步最新编辑结果
        doc.requirements = requirementTable.getRequirements()
        doc.total = doc.requirements.size

        val parser = RequirementParser(DiffSenseSettings.getInstance().toConfig())
        val json = parser.toJson(doc)
        val target = File(project.basePath, "requirements-${System.currentTimeMillis() / 1000}.json")
        target.writeText(json, Charsets.UTF_8)
        Messages.showInfoMessage(project, "已保存：${target.absolutePath}", "保存成功")
        logPanel.appendLine("需求 JSON 已保存：${target.name}")
    }

    // ==================== 扫描 Tab 动作 ====================
    /** 使用需求 Tab 拆解的结果，自动填到扫描输入 */
    private fun useLastDocument() {
        val doc = lastDocument
        if (doc == null) {
            Messages.showInfoMessage(project, "请先在需求 Tab 拆解需求", "无数据")
            return
        }
        reqJsonField.text = "(使用上次拆解结果：${doc.total} 条)"
        logPanel.appendLine("已选择使用上次拆解结果（${doc.total} 条需求）")
    }

    private fun startScan() {
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

        // 获取需求源：优先使用 lastDocument（用户点过"使用上次拆解"或直接扫描）
        val doc = resolveRequirements() ?: run {
            Messages.showWarningDialog(project, "请先拆解需求或选择 requirements.json", "缺少需求")
            return
        }

        val baseBranch = baseBranchField.text.trim().ifBlank { "develop" }

        resultTable.clear()
        TokenStats.reset()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DiffSense 扫描覆盖度", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logPanel.appendLine("开始扫描：基线 $baseBranch，需求 ${doc.requirements.size} 条")
                    val diff = DiffCollector.collectDiff(project, baseBranch)
                    if (diff.isBlank()) {
                        logPanel.appendLine("✗ 未收集到代码改动（diff 为空）")
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showWarningDialog(project, "未收集到代码改动（diff 为空），请确认基线分支正确", "无 diff")
                        }
                        return
                    }
                    logPanel.appendLine("收集到 diff：${diff.length} 字符")

                    val scanner = CoverageScanner(config, indicator)
                    val report = scanner.scan(
                        requirements = doc.requirements.filter { it.enabled },
                        diff = diff,
                        module = doc.module,
                        baseBranch = baseBranch,
                        onProgress = { line -> logPanel.appendLine(line) },
                    )
                    lastReport = report
                    logPanel.appendLine(TokenStats.report())

                    ApplicationManager.getApplication().invokeLater {
                        resultTable.showReport(report, doc.requirements)
                        val s = report.summary
                        summaryLabel.text = "覆盖 ${s.covered}/${s.total}（${(s.coverageRate * 100).toInt()}%）" +
                            " | 部分 ${s.partial} | 未覆盖 ${s.uncovered}"
                        summaryLabel.foreground = if (s.coverageRate >= 0.8) JBColor(0x2E7D32, 0x66BB6A)
                        else if (s.coverageRate >= 0.5) JBColor(0xF57F17, 0xFFA726)
                        else JBColor(0xC62828, 0xEF5350)
                        refreshToken()
                    }
                } catch (e: Exception) {
                    log.warn("扫描失败: ${e.message}")
                    logPanel.appendLine("✗ 扫描失败：${e.message}")
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, e.message ?: "未知错误", "扫描失败")
                    }
                }
            }
        })
    }

    /** 解析当前需求源：lastDocument 优先，否则尝试读 reqJsonField 指向的文件 */
    private fun resolveRequirements(): RequirementDocument? {
        lastDocument?.let { return it }
        val path = reqJsonField.text.trim()
        if (path.isBlank() || !path.startsWith("(")) {
            if (path.isBlank() || !File(path).exists()) return null
            val json = File(path).readText(Charsets.UTF_8)
            val parser = RequirementParser(DiffSenseSettings.getInstance().toConfig())
            return parser.fromJson(json)
        }
        return lastDocument
    }

    /** 导出报告为 Markdown */
    private fun exportReport() {
        val report = lastReport ?: run {
            Messages.showInfoMessage(project, "请先执行扫描", "无报告")
            return
        }
        val doc = lastDocument
        val target = File(project.basePath, "coverage-report-${System.currentTimeMillis() / 1000}.md")
        target.writeText(renderMarkdown(report, doc), Charsets.UTF_8)
        Messages.showInfoMessage(project, "报告已导出：${target.absolutePath}", "导出成功")
        logPanel.appendLine("报告已导出：${target.name}")
    }

    // ==================== 工具方法 ====================
    private fun refreshToken() {
        tokenLabel.text = "💰 Token：parse ${TokenStats.snapshot(TokenStats.Stage.PARSE).totalTokens} / " +
            "scan ${TokenStats.snapshot(TokenStats.Stage.SCAN).totalTokens}"
    }

    /** 根据覆盖度结果重新汇总（编辑后调用） */
    private fun rebuildSummary(results: List<CoverageResult>): ScanReport.Summary {
        val total = results.size
        val covered = results.count { it.covered && it.confidence == "high" }
        val partial = results.count { it.covered && it.confidence != "high" }
        val uncovered = total - covered - partial
        val rate = if (total == 0) 0.0 else covered.toDouble() / total
        return ScanReport.Summary(total, covered, uncovered, partial, rate)
    }

    /** 把报告渲染为 Markdown */
    private fun renderMarkdown(report: ScanReport, doc: RequirementDocument?): String {
        val titleById = doc?.requirements?.associateBy { it.id } ?: emptyMap()
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
        sb.appendLine("| ID | 标题 | 状态 | 置信度 | 证据 | 缺口 |")
        sb.appendLine("|----|------|------|--------|------|------|")
        report.results.forEach { r ->
            val title = titleById[r.id]?.title ?: ""
            sb.appendLine("| ${r.id} | $title | ${r.statusText()} | ${r.confidence} | ${r.evidence} | ${r.gap} |")
        }
        sb.appendLine()
        sb.appendLine("```")
        sb.appendLine(TokenStats.report())
        sb.appendLine("```")
        return sb.toString()
    }

    /**
     * 暴露日志面板，供外部（如 pre-commit handler / editor action）推送日志
     */
    fun appendLog(line: String) = logPanel.appendLine(line)

    /**
     * 暴露日志面板的清空
     */
    fun clearLog() = logPanel.clear()

    /**
     * 供外部 Action 获取当前需求源（lastDocument 优先，否则读 JSON 文件）
     */
    fun resolveRequirementsForAction(): RequirementDocument? = resolveRequirements()

    /**
     * 供外部 Action（如 Editor 右键扫描）把扫描结果推送到扫描 Tab
     */
    fun showScanReport(report: ScanReport, requirements: List<Requirement>) {
        lastReport = report
        ApplicationManager.getApplication().invokeLater {
            resultTable.showReport(report, requirements)
            val s = report.summary
            summaryLabel.text = "覆盖 ${s.covered}/${s.total}（${(s.coverageRate * 100).toInt()}%）" +
                " | 部分 ${s.partial} | 未覆盖 ${s.uncovered}"
            summaryLabel.foreground = if (s.coverageRate >= 0.8) JBColor(0x2E7D32, 0x66BB6A)
            else if (s.coverageRate >= 0.5) JBColor(0xF57F17, 0xFFA726)
            else JBColor(0xC62828, 0xEF5350)
            refreshToken()
        }
    }
}
