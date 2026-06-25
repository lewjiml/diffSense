package com.diffsense.settings

import com.intellij.openapi.options.SearchableConfigurable
import javax.swing.JComponent

/**
 * IntelliJ Settings 入口：Tools → DiffSense
 *
 * 注册于 plugin.xml：
 *   <applicationConfigurable implementation="com.diffsense.settings.DiffSenseSettingsConfigurable"/>
 */
class DiffSenseSettingsConfigurable : SearchableConfigurable {

    private val settings = DiffSenseSettings.getInstance()
    private var panel: DiffSenseSettingsPanel? = null

    override fun getId(): String = "diffsense.settings"

    override fun getDisplayName(): String = "AI DiffSense"

    override fun createComponent(): JComponent? {
        if (panel == null) {
            panel = DiffSenseSettingsPanel()
        }
        return panel?.getRoot()
    }

    override fun isModified(): Boolean {
        return panel != null
    }

    override fun apply() {
        panel?.validateInput()
        panel?.save(settings)
    }

    override fun reset() {
        panel?.load(settings)
    }

    override fun disposeUIResources() {
        panel = null
    }
}
