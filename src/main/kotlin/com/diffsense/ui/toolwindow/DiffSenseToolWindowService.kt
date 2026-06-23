package com.diffsense.ui.toolwindow

import com.diffsense.core.Requirement
import com.diffsense.core.ScanReport
import com.intellij.openapi.project.Project

/**
 * Tool Window 服务（project-level）
 *
 * 持有 ToolWindowPanel 的引用，让其他模块（如 pre-commit handler、editor action）
 * 可以向日志 Tab 推送实时进度，或向扫描 Tab 推送结果。
 */
class DiffSenseToolWindowService(val project: Project) {

    @Volatile
    var panel: DiffSenseToolWindowPanel? = null

    /** 向日志 Tab 追加一行（线程安全） */
    fun appendLog(line: String) {
        panel?.appendLog(line)
    }

    /** 把扫描报告推送到扫描 Tab（供 pre-commit 等外部调用） */
    fun showReport(report: ScanReport, requirements: List<Requirement>) {
        panel?.showScanReport(report, requirements)
    }

    companion object {
        fun getInstance(project: Project): DiffSenseToolWindowService {
            return project.getService(DiffSenseToolWindowService::class.java)
        }
    }
}
