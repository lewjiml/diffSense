package com.diffsense.ui

import com.diffsense.core.Requirement
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * 需求列表表格（可编辑）
 *
 * 列：启用 / ID / 标题 / 描述 / 关联词 / 验收标准
 *
 * 所有业务字段（标题/描述/关联词/验收）可双击编辑；
 * 启用列用勾选框。
 * 编辑后通过 [onEdited] 回调通知外部刷新。
 */
class RequirementTable(
    private val onEdited: () -> Unit = {},
) {

    private val columnNames = arrayOf("启用", "ID", "标题", "描述", "关联词", "验收标准")

    /** 可编辑列：除 ID（只读）外均可编辑 */
    private val editableCols = setOf(0, 2, 3, 4, 5)

    private val model = object : DefaultTableModel(columnNames, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = column in editableCols

        override fun getColumnClass(columnIndex: Int): Class<*> {
            if (columnIndex == 0) return java.lang.Boolean::class.java
            return java.lang.String::class.java
        }
    }

    private val table: JBTable = JBTable(model).apply {
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        rowHeight = 28
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        columnModel.getColumn(0).preferredWidth = 45
        columnModel.getColumn(1).preferredWidth = 70
        columnModel.getColumn(2).preferredWidth = 160
        columnModel.getColumn(3).preferredWidth = 280
        columnModel.getColumn(4).preferredWidth = 160
        columnModel.getColumn(5).preferredWidth = 220
    }

    private var rowReqs: List<Requirement> = emptyList()

    init {
        // 编辑事件回写到 Requirement
        model.addTableModelListener { e ->
            val row = e.firstRow
            val col = e.column
            if (row in rowReqs.indices) {
                val r = rowReqs[row]
                when (col) {
                    0 -> r.enabled = model.getValueAt(row, col) as? Boolean ?: true
                    2 -> r.title = model.getValueAt(row, col)?.toString() ?: ""
                    3 -> r.description = model.getValueAt(row, col)?.toString() ?: ""
                    4 -> r.keywords = (model.getValueAt(row, col)?.toString() ?: "")
                        .split("、", ",", "；", ";")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    5 -> r.acceptance = (model.getValueAt(row, col)?.toString() ?: "")
                        .split("\n")
                        .map { it.trim().removePrefix("- ").removePrefix("* ") }
                        .filter { it.isNotEmpty() }
                }
                if (col in editableCols) onEdited()
            }
        }
    }

    /** 获取 Swing 组件 */
    fun getComponent(): JTable = table

    /** 用需求列表填充表格 */
    fun showRequirements(reqs: List<Requirement>) {
        model.rowCount = 0
        rowReqs = reqs
        reqs.forEach { r ->
            model.addRow(arrayOf(
                r.enabled,
                r.id,
                r.title,
                r.description,
                r.keywords.joinToString("、"),
                r.acceptance.joinToString("\n") { "- $it" },
            ))
        }
    }

    /** 返回当前（可能已编辑的）需求列表 */
    fun getRequirements(): List<Requirement> = rowReqs

    /** 清空 */
    fun clear() {
        model.rowCount = 0
        rowReqs = emptyList()
    }
}
