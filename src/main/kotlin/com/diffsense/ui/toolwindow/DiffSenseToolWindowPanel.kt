package com.diffsense.ui.toolwindow

import com.diffsense.core.*
import com.diffsense.icons.DiffSenseIcons
import com.diffsense.settings.DiffSenseSettings
import com.diffsense.ui.CoverageResultTable
import com.diffsense.ui.QualityResultTable
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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * AI DiffSense Tool Window 内容面板（三 Tab）
 *
 * 三个 Tab：
 *   1. 需求 Tab —— 选 MD 文档 → 板块过滤 → 拆解需求 → 可编辑需求表格（3 列 + 详情）
 *   2. 扫描 Tab —— 选需求 JSON + 基线分支 → 扫描代码 → 可编辑结果表格（3 列 + 详情）
 *   3. 日志 Tab —— 实时输出每步进度（parse/scan 过程可见）
 *
 * v0.3.0 改进：
 *   - 改进 1：「模块名」字段改为「板块过滤」多行文本框（每行一个关键词）
 *   - 改进 4：选完 md 后实时显示「将拆解 N 个片段」预览
 *   - 改进 11：Token 累计统计（不再每次 reset），状态栏加重置按钮
 *   - 改进 12：基线分支自动检测（git symbolic-ref refs/remotes/origin/HEAD）
 */
class DiffSenseToolWindowPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    private val log = logger<DiffSenseToolWindowPanel>()

    // ==================== 共享状态 ====================
    @Volatile
    internal var lastDocument: RequirementDocument? = null

    @Volatile
    internal var lastReport: ScanReport? = null

    /** v0.8.0：最近一次代码质量扫描报告 */
    @Volatile
    internal var lastQualityReport: QualityReport? = null

    // ==================== 需求 Tab 组件 ====================
    private val reqDocField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "选择需求文档", "Markdown 格式的需求文档", project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("md")
        )
        textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateSlicePreview()
            override fun removeUpdate(e: DocumentEvent?) = updateSlicePreview()
            override fun changedUpdate(e: DocumentEvent?) = updateSlicePreview()
        })
    }

    /** 改进 1：板块过滤多行文本框（每行一个标题关键词，留空=全部） */
    private val sectionFilterArea = JBTextArea(3, 40).apply {
        emptyText.text = "板块过滤（每行一个标题关键词，留空=全部）。例：用户管理\n订单"
    }

    /** 改进 4：片段数预览标签 */
    private val slicePreviewLabel = JBLabel(" ").apply {
        foreground = JBColor.gray
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
    }

    private val requirementTable: RequirementTable = RequirementTable(
        onEdited = ::onRequirementEdited,
        onSave = ::saveRequirementsToJson,
    )

    /** 改动 6（v4）：JSON 路径常驻标签 */
    private val jsonPathLabel = JBLabel("JSON：（未保存）").apply {
        foreground = JBColor.gray
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
    }

    /** 需求 Tab：可手填/浏览的 JSON 路径输入框（方便直接载入已有 JSON 查看） */
    private val reqJsonPathField = TextFieldWithBrowseButton().apply {
        toolTipText = "可手动填写或选择已有的 requirements.json，点「载入」查看需求列表"
        addBrowseFolderListener(
            "选择需求 JSON", "选择已有的 requirements.json 直接载入", project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        )
    }

    // ==================== 扫描 Tab 组件 ====================
    private val reqJsonField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "选择需求 JSON", "parse 阶段输出的 requirements.json", project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        )
    }

    /** 问题 5b：排除路径多行输入框（每行一个 pathspec，支持目录/文件） */
    private val excludePathsArea = JBTextArea(4, 40).apply {
        emptyText.text = "排除路径（每行一个，相对仓库根）。留空=不排除"
        text = DEFAULT_EXCLUDE_PATHS.joinToString("\n")
    }

    private val resultTable = CoverageResultTable()
    private val summaryLabel = JBLabel("暂无扫描结果")

    // ---- v0.8.0 新增：代码质量扫描 ----
    /** 扫描窗口的质量开关（与 Settings 面板共享同一个持久化值） */
    private val qualityScanCheck = JBCheckBox("同时扫描代码质量（bug / 安全 / 性能 / 异味）", true).apply {
        toolTipText = "与 Settings → AI DiffSense 中的开关共享同一个配置"
    }
    private val qualityResultTable = QualityResultTable()
    private val qualitySummaryLabel = JBLabel(" ").apply {
        foreground = JBColor.gray
    }

    // ==================== 日志 Tab 组件 ====================
    private val logPanel = ScanLogPanel()

    // ==================== 底部状态栏 ====================
    private val tokenLabel = JBLabel("💰 Token：parse 0 / scan 0")

    init {
        // v0.8.0：质量扫描开关从持久化配置加载，勾选状态双向同步到 Settings
        qualityScanCheck.isSelected = DiffSenseSettings.getInstance().state.qualityScanEnabled
        qualityScanCheck.addActionListener {
            DiffSenseSettings.getInstance().state.qualityScanEnabled = qualityScanCheck.isSelected
        }

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
            .addLabeledComponent(
                "板块过滤",
                JBScrollPane(sectionFilterArea).apply {
                    preferredSize = java.awt.Dimension(400, 60)
                    border = BorderFactory.createLineBorder(JBColor.border())
                }
            )
            .addComponent(slicePreviewLabel)
            .addVerticalGap(4)
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                // 「拆解需求」+「保存为JSON」合并为「拆解并保存」
                add(JButton("▶ 拆解并保存", DiffSenseIcons.PARSE).apply {
                    toolTipText = "拆解需求并直接写入 JSON 文件，然后刷新列表"
                    addActionListener { startParseAndSave() }
                })
            })
            .addComponent(jsonPathLabel)
            .addLabeledComponent(
                "需求 JSON",
                JPanel(BorderLayout()).apply {
                    add(reqJsonPathField, BorderLayout.CENTER)
                    add(JButton("载入").apply {
                        margin = java.awt.Insets(2, 6, 2, 6)
                        toolTipText = "载入指定 JSON 文件，显示到下方需求列表"
                        addActionListener { loadRequirementsFromJson() }
                    }, BorderLayout.EAST)
                }
            )
            .addComponent(JBLabel("需求列表（双击标题/描述可直接编辑，修改后点「💾 保存到 JSON」落盘）：").apply {
                border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
                foreground = JBColor.gray
            })
            .addComponentFillVertically(requirementTable.getComponent(), 0)
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
            .addLabeledComponent(
                "排除路径",
                JBScrollPane(excludePathsArea).apply {
                    preferredSize = java.awt.Dimension(400, 80)
                    border = BorderFactory.createLineBorder(JBColor.border())
                }
            )
            // v0.8.0：代码质量扫描开关（与 Settings 共享同一持久化值）
            .addComponent(qualityScanCheck)
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
            .addComponentFillVertically(resultTable.getComponent(), 0)
            // v0.8.0：代码质量扫描结果区
            .addSeparator()
            .addComponent(JBLabel("代码质量问题").apply {
                border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
            })
            .addComponent(qualitySummaryLabel)
            .addComponent(qualityResultTable.getComponent())
            .panel

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(form).apply {
                border = BorderFactory.createEmptyBorder()
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            }, BorderLayout.CENTER)
        }
    }

    // ==================== 状态栏 ====================
    private fun buildStatusBar(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
            add(tokenLabel)
            add(JButton("重置 Token 统计").apply {
                toolTipText = "清零 parse / scan 累计的 Token 统计"
                margin = java.awt.Insets(0, 4, 0, 4)
                addActionListener {
                    TokenStats.reset()
                    refreshToken()
                    logPanel.appendLine("ℹ Token 统计已重置")
                }
            })
        }
    }

    /** 需求表格编辑后回调：同步回 lastDocument（不立即写盘，避免频繁 IO） */
    private fun onRequirementEdited() {
        lastDocument?.let { doc ->
            doc.requirements = requirementTable.getRequirements()
            doc.total = doc.requirements.size
            // 标记未保存
            val path = reqJsonField.text.trim()
            if (path.isNotBlank() && !path.startsWith("(") && File(path).exists()) {
                jsonPathLabel.text = "JSON：${File(path).name}（有未保存的修改）"
            }
        }
    }

    /** 将当前需求列表写回 JSON 文件（保存按钮触发） */
    private fun saveRequirementsToJson() {
        val doc = lastDocument
        if (doc == null) {
            Messages.showInfoMessage(project, "没有可保存的需求数据", "无数据")
            return
        }
        // 先同步最新表格内容
        doc.requirements = requirementTable.getRequirements()
        doc.total = doc.requirements.size

        // 优先使用需求 Tab 的路径，兜底用扫描 Tab 的路径
        val path = reqJsonPathField.text.trim().ifBlank { reqJsonField.text.trim() }
        if (path.isBlank() || path.startsWith("(") || !File(path).exists()) {
            Messages.showWarningDialog(project, "未找到有效的 JSON 文件路径，请先拆解并保存需求", "缺少文件")
            return
        }
        try {
            val parser = RequirementParser(DiffSenseSettings.getInstance().toConfig())
            File(path).writeText(parser.toJson(doc), Charsets.UTF_8)
            jsonPathLabel.text = "JSON：${File(path).name}（已保存）"
            // 同步两边路径
            reqJsonPathField.text = path
            reqJsonField.text = path
            logPanel.appendLine("💾 需求已保存到 JSON：${File(path).name}（${doc.total} 条）")
        } catch (e: Exception) {
            log.warn("写回 JSON 失败：${e.message}")
            Messages.showErrorDialog(project, e.message ?: "未知错误", "保存失败")
        }
    }

    /** 从需求 Tab 的 JSON 路径输入框载入已有 JSON，显示到需求列表 */
    private fun loadRequirementsFromJson() {
        val path = reqJsonPathField.text.trim()
        if (path.isBlank()) {
            Messages.showWarningDialog(project, "请先填写或选择 JSON 文件路径", "缺少路径")
            return
        }
        if (!File(path).exists()) {
            Messages.showWarningDialog(project, "文件不存在：$path", "文件无效")
            return
        }
        try {
            val json = File(path).readText(Charsets.UTF_8)
            val parser = RequirementParser(DiffSenseSettings.getInstance().toConfig())
            val doc = parser.fromJson(json)
            lastDocument = doc
            requirementTable.showRequirements(doc.requirements)
            jsonPathLabel.text = "JSON：${File(path).name}（已载入）"
            // 同步到扫描 Tab，方便后续直接扫描
            reqJsonField.text = path
            logPanel.appendLine("📂 已载入需求 JSON：${File(path).name}（${doc.total} 条）")
            Messages.showInfoMessage(project, "已载入 ${doc.total} 条需求", "载入成功")
        } catch (e: Exception) {
            log.warn("载入 JSON 失败：${e.message}")
            Messages.showErrorDialog(project, e.message ?: "未知错误", "载入失败")
        }
    }

    /** 改进 4：选完 md 后实时预览将拆解的片段数 */
    private fun updateSlicePreview() {
        val path = reqDocField.text.trim()
        if (path.isBlank() || !File(path).exists()) {
            slicePreviewLabel.text = " "
            return
        }
        try {
            val md = File(path).readText(Charsets.UTF_8)
            val keywords = parseSectionKeywords()
            val count = MarkdownSplitter.countSlices(md, keywords)
            val sections = MarkdownSplitter.listSections(md)
            val filteredInfo = if (keywords.isEmpty()) "全部 ${sections.size} 个板块"
            else "${MarkdownSplitter.filterSections(md, keywords).size}/${sections.size} 个板块命中"
            slicePreviewLabel.text = "📋 将拆解约 $count 个片段（$filteredInfo）"
        } catch (e: Exception) {
            slicePreviewLabel.text = " "
        }
    }

    /** 解析板块过滤多行文本为关键词列表 */
    private fun parseSectionKeywords(): List<String> =
        sectionFilterArea.text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ==================== 需求 Tab 动作 ====================

    /** 问题 3b：合并「拆解需求」+「保存为JSON」——拆解完成后直接落盘 JSON 并刷新列表 */
    private fun startParseAndSave() {
        val settings = DiffSenseSettings.getInstance()
        val config = settings.toConfig()

        if (config.apiKey.isBlank()) {
            Messages.showWarningDialog(
                project,
                "请先在 Settings → Tools → AI DiffSense 中配置 API Key",
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

        requirementTable.clear()
        logPanel.clear()

        val sectionKeywords = parseSectionKeywords()
        val md = mdFile.readText(Charsets.UTF_8)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI DiffSense 拆解并保存需求", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logPanel.appendLine("开始拆解需求：${mdFile.name}，板块过滤=${sectionKeywords.ifEmpty { listOf("全部") }}")
                    val parser = RequirementParser(config, indicator)
                    val doc = parser.parse(
                        md = md,
                        module = "",  // 留空让 parser 自动从板块关键词推导
                        sectionKeywords = sectionKeywords,
                        onProgress = { line -> logPanel.appendLine(line) },
                    )
                    lastDocument = doc
                    logPanel.appendLine(TokenStats.report())

                    // 问题 3b：拆解后直接落盘 JSON
                    val json = parser.toJson(doc)
                    val target = File(project.basePath, "requirements-${System.currentTimeMillis() / 1000}.json")
                    target.writeText(json, Charsets.UTF_8)
                    logPanel.appendLine("需求 JSON 已保存：${target.name}")

                    ApplicationManager.getApplication().invokeLater {
                        reqJsonField.text = target.absolutePath
                        reqJsonPathField.text = target.absolutePath
                        jsonPathLabel.text = "JSON：${target.name}"
                        requirementTable.showRequirements(doc.requirements)
                        refreshToken()
                        Messages.showInfoMessage(
                            project,
                            "需求拆解完成：共 ${doc.total} 条需求\nJSON 已保存：${target.absolutePath}",
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

    // ==================== 扫描 Tab 动作 ====================
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
                "请先在 Settings → Tools → AI DiffSense 中配置 API Key",
                "缺少 API Key"
            )
            return
        }

        val doc = resolveRequirements() ?: run {
            Messages.showWarningDialog(project, "请先拆解需求或选择 requirements.json", "缺少需求")
            return
        }

        // 问题 5b：读取排除路径
        val excludePaths = excludePathsArea.text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        resultTable.clear()
        qualityResultTable.clear()
        qualitySummaryLabel.text = " "
        // 改进 11：不再 reset，累计统计

        // v0.8.0：读取质量扫描开关（扫描窗口的勾选状态已在 init/actionListener 中同步到 Settings）
        val runQuality = qualityScanCheck.isSelected

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI DiffSense 扫描", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logPanel.appendLine("开始扫描：需求 ${doc.requirements.size} 条，git diff HEAD（排除 ${excludePaths.size} 条路径）")
                    val diff = DiffCollector.collectDiff(
                        project = project,
                        excludePaths = excludePaths,
                        onProgress = { line -> logPanel.appendLine(line) },
                    )
                    if (diff.isBlank()) {
                        logPanel.appendLine("✗ 未收集到代码改动（git diff HEAD 为空）")
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showWarningDialog(project, "未收集到代码改动（git diff HEAD 为空），请确认工作区有未提交改动", "无 diff")
                        }
                        return
                    }
                    logPanel.appendLine("收集到 diff：${diff.length} 字符")

                    // v0.9.0：Coverage + Quality 并行执行（之前是串行）
                    logPanel.appendLine("── 并行扫描启动 ──")
                    val enabledReqs = doc.requirements.filter { it.enabled }

                    val (report, qualityReport) = runBlocking {
                        coroutineScope {
                            val coverageDef = async(Dispatchers.IO) {
                                logPanel.appendLine("▶ [覆盖度] 开始：${enabledReqs.size} 条需求")
                                val scanner = CoverageScanner(config, indicator)
                                scanner.scan(
                                    requirements = enabledReqs,
                                    diff = diff,
                                    module = doc.module,
                                    onProgress = { line -> logPanel.appendLine(line) },
                                )
                            }
                            val qualityDef = if (runQuality) {
                                async(Dispatchers.IO) {
                                    logPanel.appendLine("▶ [质量] 开始扫描 diff")
                                    val qScanner = QualityScanner(config, indicator)
                                    qScanner.scan(
                                        diff = diff,
                                        onProgress = { line -> logPanel.appendLine(line) },
                                    )
                                }
                            } else {
                                null
                            }
                            coverageDef.await() to (qualityDef?.await())
                        }
                    }

                    lastReport = report
                    lastQualityReport = qualityReport
                    if (!runQuality) {
                        logPanel.appendLine("ℹ 已跳过代码质量扫描（开关未开启）")
                    }
                    logPanel.appendLine("■ 并行扫描结束")
                    logPanel.appendLine(TokenStats.report())

                    ApplicationManager.getApplication().invokeLater {
                        resultTable.showReport(report, doc.requirements)
                        val s = report.summary
                        summaryLabel.text = "覆盖 ${s.covered}/${s.total}（${(s.coverageRate * 100).toInt()}%）" +
                            " | 部分 ${s.partial} | 未覆盖 ${s.uncovered}"
                        summaryLabel.foreground = if (s.coverageRate >= 0.8) JBColor(0x2E7D32, 0x66BB6A)
                        else if (s.coverageRate >= 0.5) JBColor(0xF57F17, 0xFFA726)
                        else JBColor(0xC62828, 0xEF5350)

                        // v0.8.0：刷新质量扫描结果
                        if (runQuality && qualityReport != null) {
                            qualityResultTable.showReport(qualityReport)
                            val qs = qualityReport.summary
                            qualitySummaryLabel.text = "共 ${qs.total()} 条问题（🔴 高 ${qs.highCount} / 🟡 中 ${qs.mediumCount} / 🟢 低 ${qs.lowCount}）"
                            qualitySummaryLabel.foreground = if (qs.highCount > 0) JBColor(0xC62828, 0xEF5350)
                            else if (qs.mediumCount > 0) JBColor(0xF57F17, 0xFFA726)
                            else JBColor.gray
                        } else {
                            qualityResultTable.clear()
                            qualitySummaryLabel.text = "未启用代码质量扫描"
                        }

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
            "scan ${TokenStats.snapshot(TokenStats.Stage.SCAN).totalTokens} / " +
            "quality ${TokenStats.snapshot(TokenStats.Stage.QUALITY).totalTokens}"
    }

    /** 把报告渲染为 Markdown */
    private fun renderMarkdown(report: ScanReport, doc: RequirementDocument?): String {
        val titleById = doc?.requirements?.associateBy { it.id } ?: emptyMap()
        val sb = StringBuilder()
        sb.appendLine("# AI DiffSense 覆盖度报告")
        sb.appendLine()
        sb.appendLine("- 模块：${report.module}")
        sb.appendLine("- diff：git diff HEAD")
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

        // v0.8.0：代码质量问题
        val qReport = lastQualityReport
        if (qReport != null && qReport.issues.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## 代码质量问题")
            sb.appendLine()
            val qs = qReport.summary
            sb.appendLine("- 共 ${qs.total()} 条（高 ${qs.highCount} / 中 ${qs.mediumCount} / 低 ${qs.lowCount}）")
            sb.appendLine()
            sb.appendLine("| 严重度 | 类别 | 文件 | 描述 | 建议 |")
            sb.appendLine("|--------|------|------|------|------|")
            qReport.issues.forEach { issue ->
                sb.appendLine("| ${issue.severityText()} | ${issue.categoryText()} | ${issue.file} ${issue.lineHint} | ${issue.description} | ${issue.suggestion} |")
            }
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
     * v0.9.0：供 Pre-commit handler 读取用户在扫描 Tab 选择的 requirements.json 路径
     *
     * 返回值：
     *   - 非空字符串：用户选择的 JSON 文件绝对路径
     *   - 空：用户未选择（此时 pre-commit 会回退到项目根目录查找）
     */
    fun getReqJsonPath(): String {
        val raw = reqJsonField.text.trim()
        // "(使用上次拆解结果：...)" 这种占位文本不算路径
        return if (raw.isNotEmpty() && !raw.startsWith("(")) raw else ""
    }

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

    companion object {
        /** 问题 5b：默认排除路径（相对仓库根，支持 pathspec 通配） */
        private val DEFAULT_EXCLUDE_PATHS = listOf("*.md", "docs/", "README*", ".gitignore")
    }
}
