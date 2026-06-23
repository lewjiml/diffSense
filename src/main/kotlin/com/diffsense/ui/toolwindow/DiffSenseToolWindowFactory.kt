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
 * 创建右侧常驻工具窗，内含三个 Tab：需求 / 扫描 / 日志。
 */
class DiffSenseToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DiffSenseToolWindowPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel, "DiffSense", false)
        toolWindow.contentManager.addContent(content)

        // 把 panel 存到 project service 里，方便其他模块（如 pre-commit）推送日志
        DiffSenseToolWindowService.getInstance(project).panel = panel
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
