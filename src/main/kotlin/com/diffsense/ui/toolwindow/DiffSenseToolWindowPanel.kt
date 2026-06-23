package com.diffsense.ui.toolwindow

import com.diffsense.core.ScanReport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * DiffSense Tool Window 内容面板
 *
 * 左侧：历史报告列表
 * 右侧：选中报告的详情
 *
 * 注册于 plugin.xml：
 *   <toolWindow id="DiffSense" anchor="right"
 *               factoryClass="com.diffsense.ui.toolwindow.DiffSenseToolWindowFactory"/>
 */
class DiffSenseToolWindowPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private val listModel = DefaultListModel<ScanReport>()
    private val reportList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ReportListCellRenderer()
    }
    private val detailLabel = JBLabel("暂无报告").apply {
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    }

    init {
        val left = JPanel(BorderLayout()).apply {
            add(JBScrollPane(reportList), BorderLayout.CENTER)
            add(JBLabel("历史报告").apply {
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                foreground = JBColor.gray
            }, BorderLayout.NORTH)
        }

        val right = JPanel(BorderLayout()).apply {
            add(detailLabel, BorderLayout.CENTER)
        }

        // 分隔
        val split = com.intellij.ui.OnePixelSplitter(false, 0.3f)
        split.firstComponent = left
        split.secondComponent = right
        add(split, BorderLayout.CENTER)

        // 交互：选中报告显示详情
        reportList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val report = reportList.selectedValue
                detailLabel.text = formatReport(report)
            }
        }
    }

    /** 新增一份报告到历史列表（线程安全） */
    fun addReport(report: ScanReport) {
        ApplicationManager.getApplication().invokeLater {
            listModel.insertElementAt(report, 0)
            reportList.selectedIndex = 0
        }
    }

    /** 格式化报告详情 */
    private fun formatReport(r: ScanReport?): String {
        if (r == null) return "暂无报告"
        val sb = StringBuilder("<html><body style='padding:8px;font-family:sans-serif'>")
        sb.append("<h2>覆盖度报告</h2>")
        sb.append("<p>模块：${r.module} | 基线：${r.baseBranch} | 时间：${df.format(java.util.Date(r.timestamp))}</p>")
        val s = r.summary
        sb.append("<p>覆盖：<b>${s.covered}/${s.total}</b> （${(s.coverageRate * 100).toInt()}%）" +
            " | 部分覆盖：${s.partial} | 未覆盖：${s.uncovered}</p>")
        sb.append("<hr/>")
        sb.append("<table border='1' cellspacing='0' cellpadding='4' style='border-collapse:collapse'>")
        sb.append("<tr><th>ID</th><th>状态</th><th>置信度</th><th>证据/缺口</th></tr>")
        r.results.forEach {
            val evidence = it.evidence.ifBlank { it.gap }
            sb.append("<tr><td>${it.id}</td><td>${it.statusText()}</td><td>${it.confidence}</td><td>$evidence</td></tr>")
        }
        sb.append("</table>")
        sb.append("</body></html>")
        return sb.toString()
    }

    /** 列表项渲染器 */
    private class ReportListCellRenderer : javax.swing.ListCellRenderer<ScanReport> {
        private val df = SimpleDateFormat("MM-dd HH:mm")
        override fun getListCellRendererComponent(
            list: javax.swing.JList<out ScanReport>,
            value: ScanReport,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val label = JBLabel("${df.format(java.util.Date(value.timestamp))} - ${value.module} (${value.summary.covered}/${value.summary.total})")
            label.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            if (isSelected) {
                label.background = list.selectionBackground
                label.foreground = list.selectionForeground
            } else {
                label.background = list.background
                label.foreground = list.foreground
            }
            label.isOpaque = true
            return label
        }
    }
}
