package com.diffsense.ui

import com.diffsense.core.Requirement
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.AbstractCellEditor
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.border.EmptyBorder
import javax.swing.event.CellEditorListener
import javax.swing.event.ChangeEvent
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * 需求列表（4 列：启用 / ID / 标题 / 描述）
 *
 * v0.7.0 改进：
 *   1. 描述列使用多行文本渲染 + 动态行高，超长内容可上下滑动（单元格内 JScrollPane）
 *   2. 启用列表头放「全部启用 / 全部禁用」按钮
 *   3. 标题、描述列支持双击直接编辑，编辑后回调通知外部写回 JSON
 */
class RequirementTable(
    private val onEdited: () -> Unit = {},
    private val onSave: () -> Unit = {},
) {

    /** 列索引 */
    private object Col {
        const val ENABLED = 0
        const val ID = 1
        const val TITLE = 2
        const val DESCRIPTION = 3
    }

    /** 列名 */
    private val columnNames = arrayOf("启用", "ID", "标题", "描述")

    /** 完整数据 */
    private var allReqs: List<Requirement> = emptyList()

    private val model = object : DefaultTableModel(columnNames, 0) {
        // 启用、标题、描述列可编辑
        override fun isCellEditable(row: Int, column: Int): Boolean =
            column == Col.ENABLED || column == Col.TITLE || column == Col.DESCRIPTION

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == Col.ENABLED) java.lang.Boolean::class.java
            else java.lang.String::class.java
    }

    private val table: JBTable = JBTable(model).apply {
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        rowHeight = 56
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        tableHeader.resizingAllowed = true
        columnModel.getColumn(Col.ENABLED).preferredWidth = 45
        columnModel.getColumn(Col.ID).preferredWidth = 90
        columnModel.getColumn(Col.TITLE).preferredWidth = 220
        columnModel.getColumn(Col.DESCRIPTION).preferredWidth = 420
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // 行高可拖拽：自定义鼠标监听器，在行边界检测并调整行高
        RowHeightResizer.install(this)

        // 启用列勾选同步回数据模型
        model.addTableModelListener { e ->
            if (e.column == Col.ENABLED && e.firstRow in allReqs.indices) {
                allReqs[e.firstRow].enabled = model.getValueAt(e.firstRow, Col.ENABLED) as Boolean
                onEdited()
            } else if (e.column == Col.TITLE && e.firstRow in allReqs.indices) {
                allReqs[e.firstRow].title = model.getValueAt(e.firstRow, Col.TITLE) as String
                onEdited()
            } else if (e.column == Col.DESCRIPTION && e.firstRow in allReqs.indices) {
                allReqs[e.firstRow].description = model.getValueAt(e.firstRow, Col.DESCRIPTION) as String
                onEdited()
            }
        }
    }

    init {
        // 描述列：多行文本渲染器（JTextArea 放在 JScrollPane 里，支持上下滑动）
        table.columnModel.getColumn(Col.DESCRIPTION).cellRenderer = MultilineCellRenderer()
        // 描述列：双击编辑器（同样多行可滑动）
        table.columnModel.getColumn(Col.DESCRIPTION).cellEditor = MultilineCellEditor()

        // 标题列：双击编辑器（单行文本，DefaultTableModel 默认即可，但需确保编辑后触发 onEdited）
        table.getDefaultEditor(String::class.java).addCellEditorListener(object : CellEditorListener {
            override fun editingStopped(e: ChangeEvent?) {
                val row = table.editingRow
                val col = table.editingColumn
                if (row in allReqs.indices && col == Col.TITLE) {
                    allReqs[row].title = table.cellEditor.cellEditorValue as String
                    onEdited()
                }
            }
            override fun editingCanceled(e: ChangeEvent?) {}
        })
    }

    /** 获取整个组件：顶部工具栏（全部启用/禁用）+ 表格 */
    fun getComponent(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JButton("全部启用").apply {
                toolTipText = "勾选所有需求的启用列"
                margin = java.awt.Insets(2, 6, 2, 6)
                addActionListener { setAllEnabled(true) }
            })
            add(JButton("全部禁用").apply {
                toolTipText = "取消勾选所有需求"
                margin = java.awt.Insets(2, 6, 2, 6)
                addActionListener { setAllEnabled(false) }
            })
            add(JButton("💾 保存到 JSON").apply {
                toolTipText = "将当前需求列表保存回 JSON 文件"
                margin = java.awt.Insets(2, 6, 2, 6)
                addActionListener { onSave() }
            })
            add(JBLabel("  （双击标题/描述可直接编辑，修改后自动写回 JSON；也可手动点保存）").apply {
                foreground = JBColor.gray
                border = EmptyBorder(0, 8, 0, 0)
            })
        }

        val scrollPane = JScrollPane(table).apply {
            preferredSize = Dimension(900, 400)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }

        return JPanel(java.awt.BorderLayout()).apply {
            add(toolbar, java.awt.BorderLayout.NORTH)
            add(scrollPane, java.awt.BorderLayout.CENTER)
        }
    }

    /** 全部启用/禁用 */
    private fun setAllEnabled(enabled: Boolean) {
        if (allReqs.isEmpty()) return
        for (i in allReqs.indices) {
            model.setValueAt(enabled, i, Col.ENABLED)
        }
    }

    /** 用需求列表填充表格 */
    fun showRequirements(reqs: List<Requirement>) {
        allReqs = reqs
        repaintTable()
        if (reqs.isNotEmpty()) table.setRowSelectionInterval(0, 0)
    }

    /** 返回当前完整需求列表 */
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

    // ==================== 多行文本渲染器（描述列） ====================

    /**
     * 描述列渲染器：JTextArea 放在 JScrollPane 内，自动换行、可上下滑动。
     *
     * 行高根据内容动态调整。
     */
    private class MultilineCellRenderer : TableCellRenderer {
        private val textArea = JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            border = EmptyBorder(4, 6, 4, 6)
        }

        private val scrollPane = JScrollPane(textArea).apply {
            border = EmptyBorder(0, 0, 0, 0)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(400, 60)
        }

        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            textArea.text = value as? String ?: ""
            // 行高由用户拖拽决定（RowHeightResizer），不再自动覆盖
            return scrollPane
        }
    }

    // ==================== 多行文本编辑器（描述列，双击编辑） ====================

    /**
     * 描述列编辑器：双击进入编辑，JTextArea 可上下滚动，失焦/回车确认。
     *
     * 确认时通过 TableModelListener 触发 onEdited 回调。
     */
    private class MultilineCellEditor : AbstractCellEditor(), TableCellEditor {
        private val textArea = JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            border = EmptyBorder(4, 6, 4, 6)
        }

        private val scrollPane = JScrollPane(textArea).apply {
            border = EmptyBorder(0, 0, 0, 0)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(400, 80)
        }

        override fun getTableCellEditorComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int,
        ): Component {
            textArea.text = value as? String ?: ""
            return scrollPane
        }

        override fun getCellEditorValue(): Any = textArea.text
    }
}

// ==================== 行高拖拽支持 ====================

/**
 * 为 JTable 添加行高拖拽能力（Swing 原生不支持）。
 *
 * 在行下边界 3px 范围内按下鼠标时进入拖拽模式，上下移动调整该行高度。
 */
private object RowHeightResizer {

    private const val RESIZE_MARGIN = 3
    private const val MIN_ROW_HEIGHT = 24

    /**
     * 在指定 table 上安装行高拖拽监听器。
     */
    fun install(table: JTable) {
        var resizingRow = -1
        var startY = 0
        var startHeight = 0

        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                if (resizingRow >= 0) return
                val row = findRowAtLowerBoundary(table, e.point)
                table.cursor = if (row >= 0) Cursor(Cursor.N_RESIZE_CURSOR) else Cursor.getDefaultCursor()
            }

            override fun mouseDragged(e: MouseEvent) {
                if (resizingRow < 0) return
                val delta = e.y - startY
                val newHeight = (startHeight + delta).coerceAtLeast(MIN_ROW_HEIGHT)
                table.setRowHeight(resizingRow, newHeight)
            }
        })

        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                resizingRow = findRowAtLowerBoundary(table, e.point)
                if (resizingRow >= 0) {
                    startY = e.y
                    startHeight = table.getRowHeight(resizingRow)
                    e.consume()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                resizingRow = -1
            }
        })
    }

    /**
     * 判断 point 是否落在某行的下边界（可拖拽区域）。
     * 返回行号，若不在任何行的下边界则返回 -1。
     */
    private fun findRowAtLowerBoundary(table: JTable, point: Point): Int {
        val rowCount = table.rowCount
        if (rowCount == 0) return -1
        val visibleRect: Rectangle = table.visibleRect
        // 遍历可见行，检查 point 是否在某行底部 RESIZE_MARGIN 范围内
        for (row in 0 until rowCount) {
            val rect = table.getCellRect(row, 0, false)
            // 跳过不可见行
            if (rect.y + rect.height < visibleRect.y || rect.y > visibleRect.y + visibleRect.height) continue
            val lowerY = rect.y + rect.height
            if (point.y in (lowerY - RESIZE_MARGIN)..(lowerY + RESIZE_MARGIN) && point.x >= 0) {
                return row
            }
        }
        return -1
    }
}
