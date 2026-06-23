package com.diffsense.actions

import com.diffsense.core.*
import com.diffsense.settings.DiffSenseSettings
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
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages.showInputDialog
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import javax.swing.JOptionPane

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
 *   2. 弹文件选择器让用户选 requirements.json
 *   3. 调用 CoverageScanner 分析
 *   4. 用 Tool Window 展示结果
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

        // 让用户选择 requirements.json
        val jsonPath = Messages.showInputDialog(
            project,
            "请输入 requirements.json 的完整路径：",
            "选择需求文件",
            Messages.getQuestionIcon(),
            "",
            null
        ) ?: return

        if (jsonPath.isBlank() || !java.io.File(jsonPath).exists()) {
            Messages.showWarningDialog(project, "文件不存在：$jsonPath", "错误")
            return
        }

        // 异步扫描
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DiffSense 扫描中", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val json = java.io.File(jsonPath).readText(Charsets.UTF_8)
                    val parser = RequirementParser(DiffSenseSettings.getInstance().toConfig())
                    val doc = parser.fromJson(json)

                    val scanner = CoverageScanner(DiffSenseSettings.getInstance().toConfig(), indicator)
                    val report = scanner.scan(
                        requirements = doc.requirements.filter { it.enabled },
                        diff = selected,
                        module = "selection",
                        baseBranch = "editor-selection",
                    )

                    ApplicationManager.getApplication().invokeLater {
                        val summary = report.summary
                        Messages.showInfoMessage(
                            project,
                            """覆盖情况：
                              |覆盖 ${summary.covered}/${summary.total}（${(summary.coverageRate * 100).toInt()}%）
                              |部分覆盖 ${summary.partial}
                              |未覆盖 ${summary.uncovered}
                              |
                              |详情请查看右侧 DiffSense 工具窗
                            """.trimMargin(),
                            "扫描完成"
                        )
                    }
                } catch (ex: Exception) {
                    log.warn("扫描失败: ${ex.message}")
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
