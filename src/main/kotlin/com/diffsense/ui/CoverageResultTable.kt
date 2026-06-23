package com.diffsense.ui

import com.diffsense.core.CoverageResult
import com.diffsense.core.Requirement
import com.diffsense.core.ScanReport
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
 * 覆盖度结果（3 列 + 详情面板）
 *
 * 改进 8：5 列精简为 3 列（ID / 需求标题 / 状态），证据和缺口在选中行时
 *         显示在下方的详情面板中。
 * 改动 5（v4）：去掉搜索框、详情面板改为只读、移除 onEdited 回调。
 */
class CoverageResultTable {

    private val columnNames = arrayOf("ID", "需求标题", "状态")

    /** 完整数据 */
    private var allResults: List<CoverageResult> = emptyList()
    /** id → Requirement（用于显示标题、详情） */
    private var titleById: Map<String, Requirement> = emptyMap()

    private val model = object : DefaultTableModel(columnNames, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val table: JBTable = JBTable(model).apply {
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        rowHeight = 28
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        columnModel.getColumn(0).preferredWidth = 90
        columnModel.getColumn(1).preferredWidth = 480
        columnModel.getColumn(2).preferredWidth = 100
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    /** 详情面板：证据 / 缺口（改动 5：只读） */
    private val evidenceArea = JBTextArea(4, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        isEnabled = false
        emptyText.text = "选中上方结果行后在此查看证据"
    }
    private val gapArea = JBTextArea(4, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        isEnabled = false
        emptyText.text = "选中上方结果行后在此查看缺口"
    }

    init {
        // 状态列着色渲染器
        table.columnModel.getColumn(2).cellRenderer = StatusCellRenderer()

        // 选中行变化时刷新详情面板
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) refreshDetail()
        }
    }

    /** 获取整个组件（表格 + 只读详情面板） */
    fun getComponent(): JComponent {
        val root = JPanel(BorderLayout(0, 6))

        root.add(JBScrollPane(table).apply {
            preferredSize = java.awt.Dimension(900, 300)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        val detail = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createTitledBorder("详情")
            val fields = JPanel(BorderLayout(0, 4))
            fields.add(labeled("证据", evidenceArea), BorderLayout.NORTH)
            fields.add(labeled("缺口", gapArea), BorderLayout.CENTER)
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

    /** 用 ScanReport 填充表格 */
    fun showReport(report: ScanReport, requirements: List<Requirement>) {
        titleById = requirements.associateBy { it.id }
        allResults = report.results
        repaintTable()
        if (report.results.isNotEmpty()) table.setRowSelectionInterval(0, 0)
    }

    /** 清空 */
    fun clear() {
        allResults = emptyList()
        titleById = emptyMap()
        evidenceArea.text = ""
        gapArea.text = ""
        model.rowCount = 0
    }

    private fun repaintTable() {
        model.rowCount = 0
        allResults.forEach { r ->
            val title = titleById[r.id]?.title ?: r.id
            model.addRow(arrayOf(r.id, title, r.statusText()))
        }
    }

    private fun refreshDetail() {
        val row = table.selectedRow
        if (row in allResults.indices) {
            val r = allResults[row]
            evidenceArea.text = r.evidence
            gapArea.text = r.gap
        } else {
            evidenceArea.text = ""
            gapArea.text = ""
        }
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
