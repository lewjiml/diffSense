package com.diffsense.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Tool Window 工厂
 *
 * 注册于 plugin.xml：
 *   <toolWindow id="DiffSense" anchor="right"
 *               factoryClass="com.diffsense.ui.toolwindow.DiffSenseToolWindowFactory"/>
 *
 * 创建右侧常驻工具窗，展示历史报告。
 */
class DiffSenseToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DiffSenseToolWindowPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel, "历史报告", false)
        toolWindow.contentManager.addContent(content)

        // 把 panel 存到 project service 里，方便其他模块 addReport
        DiffSenseToolWindowService.getInstance(project).panel = panel
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
