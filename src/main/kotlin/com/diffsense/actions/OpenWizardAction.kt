package com.diffsense.actions

import com.diffsense.ui.WizardDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * 打开 DiffSense 向导弹窗
 *
 * 注册于 plugin.xml：
 *   <action id="DiffSense.OpenWizard" class="com.diffsense.actions.OpenWizardAction"
 *           text="打开 DiffSense 向导">
 *     <keyboard-shortcut keymap="$default" first-keystroke="ctrl shift D"/>
 *     <add-to-group group-id="ToolsMenu" anchor="last"/>
 *   </action>
 *
 * 入口：
 *   - Tools 菜单 → DiffSense → 打开向导
 *   - 快捷键 Ctrl+Shift+D
 */
class OpenWizardAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val dialog = WizardDialog(project)
        dialog.show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
