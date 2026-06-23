package com.diffsense.ui

import com.diffsense.core.CoverageResult
import com.diffsense.core.Requirement
import com.diffsense.core.ScanReport
import com.diffsense.icons.DiffSenseIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JTable
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * 覆盖度结果表格
 *
 * 用于在向导或 Tool Window 中展示 scan 结果。
 * 列：ID / 需求标题 / 覆盖度 / 置信度 / 证据 / 缺口
 */
class CoverageResultTable {

    private val columnNames = arrayOf("ID", "需求标题", "状态", "证据 / 缺口")

    private val model = object : DefaultTableModel(columnNames, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val table: JBTable = JBTable(model).apply {
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        rowHeight = 28
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = 70
        columnModel.getColumn(1).preferredWidth = 200
        columnModel.getColumn(2).preferredWidth = 90
        columnModel.getColumn(3).preferredWidth = 400
    }

    init {
        table.columnModel.getColumn(2).cellRenderer = StatusCellRenderer()
    }

    /** 获取 Swing 组件 */
    fun getComponent(): JTable = table

    /** 用 ScanReport 填充表格 */
    fun showReport(report: ScanReport, requirements: List<Requirement>) {
        model.rowCount = 0
        val titleByid = requirements.associateBy { it.id }
        report.results.forEach { r ->
            val req = titleByid[r.id]
            val title = req?.title ?: r.id
            val evidence = if (r.covered) r.evidence else r.gap
            model.addRow(arrayOf(r.id, title, r.statusText(), evidence))
        }
    }

    /** 清空 */
    fun clear() {
        model.rowCount = 0
    }

    /** 状态列渲染器：根据文本着色 */
    private class StatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val label = JBLabel(value?.toString() ?: "")
            label.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            when {
                value?.toString()?.startsWith("✅") == true -> label.foreground = JBColor(0x2E7D32, 0x66BB6A)
                value?.toString()?.startsWith("⚠") == true -> label.foreground = JBColor(0xF57F17, 0xFFA726)
                value?.toString()?.startsWith("❌") == true -> label.foreground = JBColor(0xC62828, 0xEF5350)
            }
            return label
        }
    }
}
