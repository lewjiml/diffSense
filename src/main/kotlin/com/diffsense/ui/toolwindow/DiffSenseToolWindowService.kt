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

    /**
     * v0.9.0：获取用户在扫描 Tab 选择的 requirements.json 路径
     *
     * 供 Pre-commit handler 使用：用户可能选了非标准路径的 JSON，
     * 这里返回其选择；未选则返回空串（调用方回退到项目根目录查找）。
     */
    fun getReqJsonPath(): String = panel?.getReqJsonPath() ?: ""

    companion object {
        fun getInstance(project: Project): DiffSenseToolWindowService {
            return project.getService(DiffSenseToolWindowService::class.java)
        }
    }
}
