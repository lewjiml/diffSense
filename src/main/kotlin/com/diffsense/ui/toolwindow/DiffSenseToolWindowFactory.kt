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
 *
 * 注意：toolWindow id 保持 "DiffSense" 不变（改 id 会让 IDE 视为新窗口，丢失用户上次停留的 Tab 等状态）；
 *      用户看到的标题栏文字通过 content.displayName = "AI DiffSense" 控制。
 */
class DiffSenseToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DiffSenseToolWindowPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel, "AI DiffSense", false)
        toolWindow.contentManager.addContent(content)

        // 把 panel 存到 project service 里，方便其他模块（如 pre-commit）推送日志
        DiffSenseToolWindowService.getInstance(project).panel = panel
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
