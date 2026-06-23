package com.diffsense.actions

import com.diffsense.core.*
import com.diffsense.settings.DiffSenseSettings
import com.diffsense.ui.toolwindow.DiffSenseToolWindowService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Editor 右键菜单：审查选中代码覆盖了哪些需求
 *
 * 注册于 plugin.xml：
 *   <action id="DiffSense.ScanSelection" class="com.diffsense.actions.ScanSelectionAction"
 *           text="审查选中代码覆盖的需求">
 *     <add-to-group group-id="EditorPopupMenu" anchor="last"/>
 *   </action>
 *
 * 工作流：
 *   1. 获取编辑器选中的文本（作为 diff 输入）
 *   2. 复用 Tool Window 中已拆解的需求（若没有则提示先拆解）
 *   3. 调用 CoverageScanner 分析，进度推送至日志 Tab
 *   4. 结果写入 Tool Window 的扫描 Tab（不弹窗）
 */
class ScanSelectionAction : AnAction() {

    private val log = logger<ScanSelectionAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selected = editor.selectionModel.selectedText
        if (selected.isNullOrBlank()) {
            Messages.showWarningDialog(project, "请先在编辑器中选中代码", "无选中内容")
            return
        }

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

        // 确保 Tool Window 可见
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("DiffSense")
        toolWindow?.show()

        val service = DiffSenseToolWindowService.getInstance(project)
        val panel = service.panel

        if (panel == null) {
            Messages.showWarningDialog(project, "DiffSense 工具窗尚未初始化，请稍后再试", "工具窗未就绪")
            return
        }

        // 获取需求源：复用 Tool Window 中已拆解的需求
        val doc = panel.resolveRequirementsForAction()
        if (doc == null || doc.requirements.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "请先在 DiffSense 工具窗的「需求」Tab 拆解需求，或选择 requirements.json",
                "缺少需求"
            )
            return
        }

        TokenStats.reset()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DiffSense 扫描选中代码", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    panel.appendLog("开始扫描选中代码：${selected.length} 字符，需求 ${doc.requirements.size} 条")
                    val scanner = CoverageScanner(config, indicator)
                    val report = scanner.scan(
                        requirements = doc.requirements.filter { it.enabled },
                        diff = selected,
                        module = doc.module,
                        onProgress = { line -> panel.appendLog(line) },
                    )
                    panel.showScanReport(report, doc.requirements)
                    panel.appendLog(TokenStats.report())

                    val s = report.summary
                    panel.appendLog(
                        "扫描完成：覆盖 ${s.covered}/${s.total}（${(s.coverageRate * 100).toInt()}%），" +
                            "部分 ${s.partial}，未覆盖 ${s.uncovered}"
                    )
                } catch (ex: Exception) {
                    log.warn("扫描失败: ${ex.message}")
                    panel.appendLog("✗ 扫描失败：${ex.message}")
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, ex.message ?: "未知错误", "扫描失败")
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && e.project != null
    }
}
