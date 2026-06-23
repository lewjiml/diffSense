package com.diffsense.ui

import com.diffsense.core.CoverageResult
import com.diffsense.core.Requirement
import com.diffsense.core.ScanReport
import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * 覆盖度结果表格
 *
 * 列：ID / 需求标题 / 状态 / 证据 / 缺口
 *
 * 证据、缺口两列可双击编辑（用户反馈：扫描出来的需求要能编辑）。
 * 编辑后通过 [onEdited] 回调通知外部刷新 ScanReport。
 */
class CoverageResultTable(
    /** 编辑后回调 */
    private val onEdited: () -> Unit = {},
) {

    private val columnNames = arrayOf("ID", "需求标题", "状态", "证据", "缺口")

    /** 列索引：证据、缺口可编辑 */
    private val editableCols = setOf(3, 4)

    private val model = object : DefaultTableModel(columnNames, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = column in editableCols
    }

    private val table: JBTable = JBTable(model).apply {
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        rowHeight = 28
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        columnModel.getColumn(0).preferredWidth = 70
        columnModel.getColumn(1).preferredWidth = 180
        columnModel.getColumn(2).preferredWidth = 80
        columnModel.getColumn(3).preferredWidth = 250
        columnModel.getColumn(4).preferredWidth = 250
    }

    /** 行 → CoverageResult 映射，供编辑后回写 */
    private var rowResults: List<CoverageResult> = emptyList()

    init {
        // 状态列着色
        table.columnModel.getColumn(2).cellRenderer = StatusCellRenderer()
        // 监听编辑事件，回写模型
        model.addTableModelListener { e ->
            val row = e.firstRow
            val col = e.column
            if (row in rowResults.indices && col in editableCols) {
                val r = rowResults[row]
                val newValue = model.getValueAt(row, col)?.toString() ?: ""
                when (col) {
                    3 -> r.evidence = newValue
                    4 -> r.gap = newValue
                }
                onEdited()
            }
        }
    }

    /** 获取 Swing 组件 */
    fun getComponent(): JTable = table

    /** 用 ScanReport 填充表格 */
    fun showReport(report: ScanReport, requirements: List<Requirement>) {
        model.rowCount = 0
        val titleById = requirements.associateBy { it.id }
        rowResults = report.results
        report.results.forEach { r ->
            val req = titleById[r.id]
            val title = req?.title ?: r.id
            model.addRow(arrayOf(r.id, title, r.statusText(), r.evidence, r.gap))
        }
    }

    /** 清空 */
    fun clear() {
        model.rowCount = 0
        rowResults = emptyList()
    }

    /** 状态列渲染器：根据文本着色 */
    private class StatusCellRenderer : JLabel(), TableCellRenderer {
        init {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        }
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val text = value?.toString() ?: ""
            setText(text)
            when {
                text.startsWith("✅") -> foreground = JBColor(0x2E7D32, 0x66BB6A)
                text.startsWith("⚠") -> foreground = JBColor(0xF57F17, 0xFFA726)
                text.startsWith("❌") -> foreground = JBColor(0xC62828, 0xEF5350)
                else -> foreground = table.foreground
            }
            return this
        }
    }
}
