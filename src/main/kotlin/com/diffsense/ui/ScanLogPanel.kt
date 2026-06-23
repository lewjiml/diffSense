package com.diffsense.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * 扫描日志面板（append-only）
 *
 * 接收 [com.diffsense.core.RequirementParser] 和 [com.diffsense.core.CoverageScanner]
 * 通过 onProgress 回调推送的实时日志，逐行展示。
 *
 * 用户反馈第 5 条：希望能看到扫描过程。
 */
class ScanLogPanel {

    private val df = SimpleDateFormat("HH:mm:ss")
    private val area = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        // 等宽字体，便于对齐日志
        val base = font
        font = java.awt.Font(java.awt.Font.MONOSPACED, base.style, base.size)
    }

    private val scrollPane = JBScrollPane(area).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val panel = JPanel(BorderLayout()).apply {
        add(scrollPane, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(JButton("清空日志").apply {
                addActionListener { clear() }
            })
        }, BorderLayout.SOUTH)
    }

    /** 获取 Swing 组件 */
    fun getComponent(): JPanel = panel

    /**
     * 追加一条日志（线程安全，自动切到 EDT）
     *
     * @param line 日志内容（不含时间戳，自动补）
     */
    fun appendLine(line: String) {
        val ts = df.format(java.util.Date())
        val full = "[$ts] $line\n"
        ApplicationManager.getApplication().invokeLater {
            area.append(full)
            // 自动滚动到底部
            area.caretPosition = area.document.length
        }
    }

    /** 清空日志 */
    fun clear() {
        ApplicationManager.getApplication().invokeLater {
            area.text = ""
        }
    }
}
