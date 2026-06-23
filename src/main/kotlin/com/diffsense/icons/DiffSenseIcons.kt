package com.diffsense.icons

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * DiffSense 图标统一入口
 *
 * 目前复用 IntelliJ 自带图标，后续可替换为自定义 SVG。
 * 使用方式：DiffSenseIcons.TOOL_WINDOW
 */
object DiffSenseIcons {

    /** Tool Window 主图标（右侧栏） */
    @JvmField
    val TOOL_WINDOW: Icon = AllIcons.Toolwindows.ToolWindowProject

    /** 向导 / 打开按钮 */
    @JvmField
    val OPEN_WIZARD: Icon = AllIcons.Actions.Execute

    /** Parse（分割需求） */
    @JvmField
    val PARSE: Icon = AllIcons.Actions.SplitVertically

    /** Scan（扫描代码） */
    @JvmField
    val SCAN: Icon = AllIcons.Actions.Search

    /** Run（一键执行） */
    @JvmField
    val RUN: Icon = AllIcons.Actions.Execute

    /** 已覆盖（绿色勾） */
    @JvmField
    val COVERED: Icon = AllIcons.General.InspectionsOK

    /** 未覆盖（红色叉） */
    @JvmField
    val UNCOVERED: Icon = AllIcons.General.Error

    /** 部分覆盖（黄色警告） */
    @JvmField
    val PARTIAL: Icon = AllIcons.General.Warning

    /** Token 消耗 */
    @JvmField
    val TOKEN: Icon = AllIcons.General.Information

    /** 加载中 */
    @JvmField
    val LOADING: Icon = AllIcons.Process.Step_passive
}
