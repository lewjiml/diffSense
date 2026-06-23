package com.diffsense.ui.toolwindow

import com.diffsense.core.ScanReport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService

/**
 * Tool Window 服务（project-level）
 *
 * 持有 ToolWindowPanel 的引用，让其他模块（如 WizardDialog、Pre-commit）
 * 可以把新报告添加到历史列表中。
 */
class DiffSenseToolWindowService(val project: Project) {

    @Volatile
    var panel: DiffSenseToolWindowPanel? = null

    /** 添加一份报告（线程安全） */
    fun addReport(report: ScanReport) {
        val p = panel ?: return
        ApplicationManager.getApplication().invokeLater {
            p.addReport(report)
        }
    }

    companion object {
        fun getInstance(project: Project): DiffSenseToolWindowService {
            return project.getService(DiffSenseToolWindowService::class.java)
        }
    }
}
