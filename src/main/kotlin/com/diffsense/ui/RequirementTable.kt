package com.diffsense.ui

import com.diffsense.core.Requirement
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

/**
 * 需求列表（3 列 + 详情面板）
 *
 * 改进 3：6 列精简为 3 列（启用 / ID / 标题），其余字段（描述 / 关联词 / 验收标准）
 *         在选中某行时显示在下方的详情面板中，可编辑。
 * 改进 7：顶部提供搜索框，实时过滤（按 ID / 标题 / 描述包含匹配）。
 */
class RequirementTable(
    private val onEdited: () -> Unit = {},
) {

    /** 列索引：启用 / ID / 标题 */
    private val columnNames = arrayOf("启用", "ID", "标题")
    private val editableCols = setOf(0, 2)

    /** 完整数据（未过滤） */
    private var allReqs: List<Requirement> = emptyList()

    /** 当前显示的数据（可能被搜索过滤） */
    private var rowReqs: List<Requirement> = emptyList()

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
        columnModel.getColumn(1).preferredWidth = 90
        columnModel.getColumn(2).preferredWidth = 520
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    /** 搜索框 */
    private val searchField = JBTextField().apply {
        emptyText.text = "搜索（ID / 标题 / 描述，不区分大小写）"
    }

    /** 详情面板：描述 / 关联词 / 验收标准（可编辑） */
    private val descArea = JBTextArea(4, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "选中上方需求行后在此编辑描述"
    }
    private val keywordsField = JBTextField().apply {
        emptyText.text = "关联词，用顿号（、）分隔"
    }
    private val acceptanceArea = JBTextArea(5, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "验收标准，每行一条"
    }

    /** 详情面板编辑回调：把详情写回当前选中的 Requirement */
    private fun attachDetailListeners() {
        val update: () -> Unit = {
            val row = table.selectedRow
            if (row in rowReqs.indices) {
                val r = rowReqs[row]
                r.description = descArea.text
                r.keywords = keywordsField.text
                    .split("、", ",", "；", ";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                r.acceptance = acceptanceArea.text
                    .lines()
                    .map { it.trim().removePrefix("- ").removePrefix("* ") }
                    .filter { it.isNotEmpty() }
                onEdited()
            }
        }
        listOf(descArea, keywordsField, acceptanceArea).forEach { c ->
            c.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = update()
                override fun removeUpdate(e: DocumentEvent?) = update()
                override fun changedUpdate(e: DocumentEvent?) = update()
            })
        }
    }

    init {
        attachDetailListeners()

        // 列表编辑事件回写（启用 / 标题）
        model.addTableModelListener { e ->
            val row = e.firstRow
            val col = e.column
            if (row in rowReqs.indices && col in editableCols) {
                val r = rowReqs[row]
                when (col) {
                    0 -> r.enabled = model.getValueAt(row, col) as? Boolean ?: true
                    2 -> r.title = model.getValueAt(row, col)?.toString() ?: ""
                }
                onEdited()
            }
        }

        // 选中行变化时，刷新详情面板
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) refreshDetail()
        }

        // 搜索框实时过滤
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })
    }

    /** 应用搜索过滤（保留 allReqs 的完整数据，仅过滤显示） */
    private fun applyFilter() {
        val kw = searchField.text.trim()
        val selectedId = if (table.selectedRow in rowReqs.indices) rowReqs[table.selectedRow].id else null
        rowReqs = if (kw.isEmpty()) allReqs
        else allReqs.filter { r ->
            r.id.contains(kw, ignoreCase = true) ||
                r.title.contains(kw, ignoreCase = true) ||
                r.description.contains(kw, ignoreCase = true)
        }
        repaintTable()
        // 尝试恢复之前选中的行
        if (selectedId != null) {
            val idx = rowReqs.indexOfFirst { it.id == selectedId }
            if (idx >= 0) table.setRowSelectionInterval(idx, idx)
        }
    }

    /** 获取整个组件（顶部搜索框 + 表格 + 底部详情面板） */
    fun getComponent(): JComponent {
        val root = JPanel(BorderLayout(0, 6))

        // 顶部搜索条
        val topBar = JPanel(BorderLayout(4, 0)).apply {
            add(JBLabel("🔍"), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }
        root.add(topBar, BorderLayout.NORTH)

        // 中部表格
        root.add(JBScrollPane(table).apply {
            preferredSize = java.awt.Dimension(900, 260)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        // 底部详情面板
        val detail = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createTitledBorder("详情（可编辑）")
            val fields = JPanel(BorderLayout(0, 4))
            fields.add(labeled("描述", descArea), BorderLayout.NORTH)
            val mid = JPanel(BorderLayout(0, 4))
            mid.add(labeled("关联词", keywordsField), BorderLayout.NORTH)
            mid.add(labeled("验收标准", acceptanceArea), BorderLayout.CENTER)
            fields.add(mid, BorderLayout.CENTER)
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

    /** 用需求列表填充表格 */
    fun showRequirements(reqs: List<Requirement>) {
        allReqs = reqs
        rowReqs = reqs
        searchField.text = ""
        repaintTable()
        if (reqs.isNotEmpty()) table.setRowSelectionInterval(0, 0)
    }

    /** 返回当前（可能已编辑的）完整需求列表 */
    fun getRequirements(): List<Requirement> = allReqs

    /** 清空 */
    fun clear() {
        allReqs = emptyList()
        rowReqs = emptyList()
        searchField.text = ""
        descArea.text = ""
        keywordsField.text = ""
        acceptanceArea.text = ""
        model.rowCount = 0
    }

    /** 重新绘制表格（保持 allReqs 不变） */
    private fun repaintTable() {
        model.rowCount = 0
        rowReqs.forEach { r ->
            model.addRow(arrayOf(r.enabled, r.id, r.title))
        }
    }

    /** 刷新详情面板内容（根据当前选中行） */
    private fun refreshDetail() {
        val row = table.selectedRow
        if (row in rowReqs.indices) {
            val r = rowReqs[row]
            descArea.text = r.description
            keywordsField.text = r.keywords.joinToString("、")
            acceptanceArea.text = r.acceptance.joinToString("\n") { "- $it" }
        } else {
            descArea.text = ""
            keywordsField.text = ""
            acceptanceArea.text = ""
        }
    }
}
