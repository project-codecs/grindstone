package com.codex.idea.grindstone.wizard.component

import com.codex.idea.grindstone.generator.model.DependencyEntry
import com.codex.idea.grindstone.generator.model.DependencyVersionSpec
import com.codex.idea.grindstone.utility.versions.MavenVersions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.observable.util.not
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor

class DependencyDialog(private val mcVersions: List<String>, private val existingEntry: DependencyEntry? = null) : DialogWrapper(true) {
    private val graph = PropertyGraph()

    private val isSingleModeProperty = graph.property(existingEntry?.versionSpec !is DependencyVersionSpec.PerVersion)

    private val modIdField = JTextField(existingEntry?.modId ?: "", 30)
    private val addToFmjCheckbox = JBCheckBox("Add to fabric.mod.json depends", existingEntry?.addToFabricModJson ?: true)
    private val coordinateField = JTextField(existingEntry?.mavenCoordinate ?: "", 40)
    private val repoUrlField = JTextField(existingEntry?.mavenRepo ?: "https://maven.fabricmc.net/", 40)

    private val singleVersionCombo  = ComboBox<String>()
    private val fetchButton = JButton("Fetch versions")
    private val fetchingIcon = AsyncProcessIcon("DependencyDialogFetch")
    private val fetchStatusLabel = JBLabel("").also { it.foreground = JBColor.RED }

    private lateinit var perVersionTableModel: PerVersionTableModel
    private lateinit var perVersionTable: JTable
    private val perVersionPanel = JPanel(BorderLayout())

    private var availableVersions: List<String> = emptyList()

    init {
        title = if (existingEntry == null) "Add Dependency" else "Edit Dependency"
        init()

        when (val spec = existingEntry?.versionSpec) {
            is DependencyVersionSpec.Single -> {
                singleVersionCombo.addItem(spec.version)
                singleVersionCombo.selectedItem = spec.version
            }
            else -> { /* per-version handled with the table */ }
        }

        isSingleModeProperty.afterChange { updateSwingEnabledState() }
        fetchButton.addActionListener { fetchVersions() }
        addToFmjCheckbox.addActionListener { modIdField.isEnabled = addToFmjCheckbox.isSelected }

        modIdField.isEnabled = addToFmjCheckbox.isSelected
        fetchingIcon.isVisible = false
    }

    override fun createCenterPanel(): JComponent {
        perVersionTableModel = PerVersionTableModel(mcVersions, buildInitialPerVersionMap())
        perVersionTable = JBTable(perVersionTableModel).apply {
            setDefaultEditor(String::class.java, VersionComboEditor())
            rowHeight = 28
            preferredScrollableViewportSize = Dimension(500, mcVersions.size.coerceAtMost(8) * rowHeight)
        }
        perVersionPanel.add(JBScrollPane(perVersionTable), BorderLayout.CENTER)

        return panel {
            group("Mod Metadata") {
                row("Maven coordinate:") {
                    @Suppress("DialogTitleCapitalization")
                    cell(coordinateField).align(AlignX.FILL)
                        .comment("e.g. net.fabricmc.fabric-api:fabric-api")
                }
                row("Maven repository URL:") {
                    @Suppress("DialogTitleCapitalization")
                    cell(repoUrlField).align(AlignX.FILL)
                        .comment("e.g. https://maven.fabricmc.net/")
                }
                row { cell(addToFmjCheckbox) }
                row("Mod ID (fabric.mod.json):") {
                    cell(modIdField).align(AlignX.FILL)
                        .comment("The id used in the depends block, e.g. fabric-api")
                }
            }

            group("Version") {
                buttonsGroup {
                    row {
                        radioButton("Single version for all sub-versions", value = true)
                        radioButton("Separate version per Minecraft version", value = false)
                    }
                }.bind(getter = { isSingleModeProperty.get() }, setter = { isSingleModeProperty.set(it) })

                row("Version:") {
                    cell(singleVersionCombo).align(AlignX.FILL)
                    cell(fetchButton)
                    cell(fetchingIcon)
                    cell(fetchStatusLabel)
                }.visibleIf(isSingleModeProperty)

                row {
                    cell(perVersionPanel).align(AlignX.FILL)
                        .comment("Select a version for each Minecraft sub-version. Fetch versions first.")
                }.visibleIf(isSingleModeProperty.not())

                row {
                    button("Fetch Versions For All") { fetchVersions() }
                        .comment("Downloads available versions from the Maven repo above")
                }.visibleIf(isSingleModeProperty.not())
            }
        }.also {
            it.preferredSize = Dimension(620, -1)
        }
    }

    private fun updateSwingEnabledState() {
        val singleMode = isSingleModeProperty.get()

        singleVersionCombo.isEnabled = singleMode
        perVersionTable.isEnabled = !singleMode
    }

    private fun buildInitialPerVersionMap(): Map<String, String> = (existingEntry?.versionSpec as? DependencyVersionSpec.PerVersion)?.versions ?: mcVersions.associateWith { "" }

    private fun fetchVersions() {
        val coordinate = coordinateField.text.trim()
        val repoUrl = repoUrlField.text.trim()

        val split = MavenVersions.splitCoordinate(coordinate)
        if (split == null) {
            fetchStatusLabel.text = "Invalid coordinate (expected group:artifact)"
            return
        }

        fetchStatusLabel.text = ""
        fetchButton.isEnabled = false
        fetchingIcon.isVisible = true
        fetchingIcon.resume()

        val (groupId, artifactId) = split

        ApplicationManager.getApplication().executeOnPooledThread {
            val versions = MavenVersions.fetchVersions(repoUrl, groupId, artifactId)
            SwingUtilities.invokeLater {
                fetchingIcon.suspend()
                fetchingIcon.isVisible = false
                fetchButton.isEnabled = true
                availableVersions = versions

                if (versions.isEmpty()) {
                    fetchStatusLabel.text = "No versions found, check repo URL and coordinate"
                    return@invokeLater
                }

                singleVersionCombo.removeAllItems()
                versions.forEach { singleVersionCombo.addItem(it) }
                val currentSingle = (existingEntry?.versionSpec as? DependencyVersionSpec.Single)?.version
                if (currentSingle != null && versions.contains(currentSingle)) singleVersionCombo.selectedItem = currentSingle

                perVersionTableModel.setAvailableVersions(versions)
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        val coord = coordinateField.text.trim()
        if (coord.isEmpty()) return ValidationInfo("Maven coordinate is required", coordinateField)
        if (MavenVersions.splitCoordinate(coord) == null) return ValidationInfo("Format must be groupId:artifactId", coordinateField)

        val repo = repoUrlField.text.trim()
        if (repo.isEmpty()) return ValidationInfo("Maven repository URL is required", repoUrlField)
        if (!repo.startsWith("http")) return ValidationInfo("Repo URL must start with http(s)://", repoUrlField)

        if (addToFmjCheckbox.isSelected && modIdField.text.isBlank()) return ValidationInfo("Mod ID is required when adding to fabric.mod.json", modIdField)
        if (isSingleModeProperty.get() && singleVersionCombo.selectedItem == null) return ValidationInfo("Select or fetch a version", singleVersionCombo)

        return null
    }

    fun getResult(): DependencyEntry {
        val versionSpec: DependencyVersionSpec = if (isSingleModeProperty.get()) DependencyVersionSpec.Single(singleVersionCombo.selectedItem as String)
                                                 else DependencyVersionSpec.PerVersion(perVersionTableModel.getVersionMap())

        return DependencyEntry(
            modId = modIdField.text.trim(),
            addToFabricModJson = addToFmjCheckbox.isSelected,
            mavenCoordinate = coordinateField.text.trim(),
            mavenRepo = repoUrlField.text.trim(),
            versionSpec = versionSpec,
        )
    }
}

private class PerVersionTableModel(private val mcVersions: List<String>, initialMap: Map<String, String>) : AbstractTableModel() {
    private val selected = LinkedHashMap<String, String>().apply { mcVersions.forEach { put(it, initialMap[it] ?: "") } }

    private var availableVersions: List<String> = emptyList()

    fun setAvailableVersions(versions: List<String>) {
        availableVersions = versions
        fireTableDataChanged()
    }

    fun getVersionMap(): Map<String, String> = LinkedHashMap(selected)

    fun getAvailableVersions(): List<String> = availableVersions

    override fun getRowCount() = mcVersions.size
    override fun getColumnCount() = 2
    override fun getColumnName(column: Int) = if (column == 0) "Minecraft Version" else "Dependency Version"
    override fun getColumnClass(column: Int) = String::class.java
    override fun isCellEditable(row: Int, column: Int) = column == 1

    override fun getValueAt(row: Int, column: Int): Any {
        val mcVersion = mcVersions[row]
        return if (column == 0) mcVersion else selected[mcVersion] ?: ""
    }

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        if (column != 1) return

        selected[mcVersions[row]] = value?.toString().orEmpty()
        fireTableCellUpdated(row, column)
    }
}

private class VersionComboEditor : AbstractCellEditor(), TableCellEditor {
    private val combo = ComboBox<String>()

    override fun getTableCellEditorComponent(
        table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
    ): java.awt.Component {
        val model = table.model as? PerVersionTableModel
        combo.removeAllItems()
        combo.addItem("")
        model?.getAvailableVersions()?.forEach { combo.addItem(it) }
        combo.selectedItem = value?.toString()?.takeIf { it.isNotEmpty() } ?: ""
        return combo
    }

    override fun getCellEditorValue(): Any = combo.selectedItem ?: ""
}
