package com.diffsense.ui

import com.diffsense.core.Requirement
import com.intellij.ui.table.JBTable
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.table.DefaultTableModel

/**
 * 需求列表（4 列，启用列可勾选）
 *
 * 问题 3a：精简为只读表格，去掉搜索框、关联词列、详情面板。
 * 改动 4（v4）：放开「启用」列可勾选，其余列只读。
 *   - 4 列：启用 / ID / 标题 / 描述
 *   - 编辑需求内容请直接改 JSON 文件后点「从 JSON 更新列表」
 */
class RequirementTable(
    private val onEdited: () -> Unit = {},
) {

    /** 列名：启用 / ID / 标题 / 描述 */
    private val columnNames = arrayOf("启用", "ID", "标题", "描述")

    /** 完整数据 */
    private var allReqs: List<Requirement> = emptyList()

    private val model = object : DefaultTableModel(columnNames, 0) {
        // 改动 4（v4）：放开「启用」列可编辑，其余列只读
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == 0) java.lang.Boolean::class.java
            else java.lang.String::class.java
        }
    }

    private val table: JBTable = JBTable(model).apply {
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        rowHeight = 28
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        columnModel.getColumn(0).preferredWidth = 45
        columnModel.getColumn(1).preferredWidth = 90
        columnModel.getColumn(2).preferredWidth = 260
        columnModel.getColumn(3).preferredWidth = 460
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // 改动 4（v4）：启用列勾选同步回数据模型
        model.addTableModelListener { e ->
            if (e.column == 0 && e.firstRow in allReqs.indices) {
                allReqs[e.firstRow].enabled = model.getValueAt(e.firstRow, 0) as Boolean
                onEdited()
            }
        }
    }

    /** 获取整个组件（仅表格） */
    fun getComponent(): JComponent {
        return JScrollPane(table).apply {
            preferredSize = Dimension(900, 400)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
    }

    /** 用需求列表填充表格 */
    fun showRequirements(reqs: List<Requirement>) {
        allReqs = reqs
        repaintTable()
        if (reqs.isNotEmpty()) table.setRowSelectionInterval(0, 0)
    }

    /** 返回当前完整需求列表（只读，直接返回原数据） */
    fun getRequirements(): List<Requirement> = allReqs

    /** 清空 */
    fun clear() {
        allReqs = emptyList()
        model.rowCount = 0
    }

    /** 重新绘制表格 */
    private fun repaintTable() {
        model.rowCount = 0
        allReqs.forEach { r ->
            model.addRow(arrayOf(r.enabled, r.id, r.title, r.description))
        }
    }
}
