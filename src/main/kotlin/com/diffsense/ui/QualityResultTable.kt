package com.diffsense.ui

import com.diffsense.core.QualityIssue
import com.diffsense.core.QualityReport
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * 代码质量问题结果表格（v0.8.0 新增）
 *
 * 列：严重度 / 类别 / 文件 / 描述（选中行后下方显示完整描述 + 修复建议）。
 *
 * 设计参考 [CoverageResultTable]：
 * - 3+1 列精简展示，详情在底部面板
 * - 严重度列着色（高=红 / 中=黄 / 低=绿）
 */
class QualityResultTable {

    private val columnNames = arrayOf("严重度", "类别", "文件", "描述")

    private var allIssues: List<QualityIssue> = emptyList()

    private val model = object : DefaultTableModel(columnNames, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val table: JBTable = JBTable(model).apply {
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        rowHeight = 28
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        columnModel.getColumn(0).preferredWidth = 80
        columnModel.getColumn(1).preferredWidth = 90
        columnModel.getColumn(2).preferredWidth = 260
        columnModel.getColumn(3).preferredWidth = 400
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    /** 详情面板：完整描述 / 修复建议 */
    private val descArea = JBTextArea(4, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        isEnabled = false
        emptyText.text = "选中上方问题行后在此查看完整描述"
    }
    private val suggestionArea = JBTextArea(4, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        isEnabled = false
        emptyText.text = "选中上方问题行后在此查看修复建议"
    }

    init {
        // 严重度列着色渲染器
        table.columnModel.getColumn(0).cellRenderer = SeverityCellRenderer()

        // 选中行变化时刷新详情面板
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) refreshDetail()
        }
    }

    /** 获取整个组件（表格 + 只读详情面板） */
    fun getComponent(): JComponent {
        val root = JPanel(BorderLayout(0, 6))

        root.add(JBScrollPane(table).apply {
            preferredSize = java.awt.Dimension(900, 240)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        val detail = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createTitledBorder("详情")
            val fields = JPanel(BorderLayout(0, 4))
            fields.add(labeled("描述", descArea), BorderLayout.NORTH)
            fields.add(labeled("建议", suggestionArea), BorderLayout.CENTER)
            add(fields, BorderLayout.CENTER)
        }
        root.add(detail, BorderLayout.SOUTH)

        return root
    }

    private fun labeled(label: String, comp: Component): JPanel {
        return JPanel(BorderLayout(6, 0)).apply {
            add(JBLabel(label).apply {
                foreground = JBColor.gray
                border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            }, BorderLayout.WEST)
            add(comp, BorderLayout.CENTER)
        }
    }

    /** 用 QualityReport 填充表格 */
    fun showReport(report: QualityReport) {
        allIssues = report.issues
        repaintTable()
        if (report.issues.isNotEmpty()) table.setRowSelectionInterval(0, 0)
    }

    /** 清空 */
    fun clear() {
        allIssues = emptyList()
        descArea.text = ""
        suggestionArea.text = ""
        model.rowCount = 0
    }

    private fun repaintTable() {
        model.rowCount = 0
        allIssues.forEach { issue ->
            model.addRow(arrayOf(
                issue.severityText(),
                issue.categoryText(),
                issue.file + "  " + issue.lineHint,
                issue.description,
            ))
        }
    }

    private fun refreshDetail() {
        val row = table.selectedRow
        if (row in allIssues.indices) {
            val issue = allIssues[row]
            descArea.text = issue.description
            suggestionArea.text = issue.suggestion
        } else {
            descArea.text = ""
            suggestionArea.text = ""
        }
    }

    /** 严重度列渲染器：根据文本着色 */
    private class SeverityCellRenderer : JLabel(), TableCellRenderer {
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
                text.startsWith("🔴") -> foreground = JBColor(0xC62828, 0xEF5350)
                text.startsWith("🟡") -> foreground = JBColor(0xF57F17, 0xFFA726)
                text.startsWith("🟢") -> foreground = JBColor(0x2E7D32, 0x66BB6A)
                else -> foreground = table.foreground
            }
            return this
        }
    }
}
