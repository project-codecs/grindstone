package com.codex.idea.grindstone.wizard.component

import com.codex.idea.grindstone.generator.model.DependencyEntry
import com.codex.idea.grindstone.generator.model.DependencyVersionSpec
import com.codex.idea.grindstone.utility.translate
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

class DependencyListDialog(dialogTitle: String, private val mcVersions: List<String>, initial: List<DependencyEntry> = emptyList()) : DialogWrapper(true) {
    private val listModel = DefaultListModel<DependencyEntry>().also { model ->
        initial.forEach(model::addElement)
    }

    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = DependencyListCellRenderer()
    }

    init {
        title = dialogTitle
        init()
    }

    override fun createCenterPanel(): JComponent =
        ToolbarDecorator.createDecorator(list)
            .setAddAction { addDependency() }
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .setEditActionUpdater { list.selectedIndex >= 0 }
            .setRemoveActionUpdater { list.selectedIndex >= 0 }
            .disableUpDownActions()
            .createPanel()
            .also { it.preferredSize = Dimension(640, 420) }

    private fun addDependency() {
        val dialog = DependencyDialog(mcVersions)
        if (dialog.showAndGet()) {
            listModel.addElement(dialog.getResult())
        }
    }

    private fun editSelected() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val dialog = DependencyDialog(mcVersions, listModel.getElementAt(index))

        if (dialog.showAndGet()) listModel.setElementAt(dialog.getResult(), index)
    }

    private fun removeSelected() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        listModel.removeElementAt(index)
    }

    override fun doValidate(): ValidationInfo? {
        val entries = getResult()
        return if (entries.distinctBy(DependencyEntry::modId).size == entries.size) null
        else ValidationInfo(translate("dependency.error.duplicateId"), list)
    }

    fun getResult(): List<DependencyEntry> = List(listModel.size) { listModel.getElementAt(it) }
}

private class DependencyListCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        val text = (value as? DependencyEntry)?.let { entry ->
            buildString {
                append(entry.mavenCoordinate)

                when (val spec = entry.versionSpec) {
                    is DependencyVersionSpec.Single ->
                        append(" [${spec.version}]")

                    is DependencyVersionSpec.Multi ->
                        append(" [${translate("dependency.perVersion", params = arrayOf(spec.versions.size))}]")
                }

                if (entry.addToFabricModJson) {
                    append(" - fabric.mod.json")
                }
            }
        } ?: "-"

        return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
    }
}
