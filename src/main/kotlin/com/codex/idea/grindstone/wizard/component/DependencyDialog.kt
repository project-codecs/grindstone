package com.codex.idea.grindstone.wizard.component

import com.codex.idea.grindstone.generator.model.DependencyEntry
import com.codex.idea.grindstone.generator.model.DependencyVersionSpec
import com.codex.idea.grindstone.utility.translate
import com.codex.idea.grindstone.utility.versions.MavenVersions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.BorderLayout
import java.awt.Dimension
import java.net.URI
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor

class DependencyDialog(private val mcVersions: List<String>, private val existingEntry: DependencyEntry? = null) : DialogWrapper(true) {
    private val graph = PropertyGraph()

    private val isSingleModeProperty = graph.property(existingEntry?.versionSpec !is DependencyVersionSpec.Multi)

    private val modIdField = JTextField(existingEntry?.modId ?: "", 30)
    private val addToFmjCheckbox = JBCheckBox(translate("dependency.dialog.addToFmj"), existingEntry?.addToFabricModJson ?: true)
    private val coordinateField = JTextField(existingEntry?.mavenCoordinate ?: "", 40)
    private val repoUrlField = JTextField(existingEntry?.repositories()?.joinToString(", ").orEmpty(), 40)
    private val remapCheckbox = JBCheckBox(translate("dependency.dialog.remap"), existingEntry?.remap ?: true)
    private val includeCheckbox = JBCheckBox(translate("dependency.dialog.include"), existingEntry?.includeInJar ?: false)

    private val singleVersionCombo = ComboBox<String>().apply { isEditable = true }
    private val fetchButton = JButton(translate("dependency.dialog.fetch"))
    private val perVersionFetchButton = JButton(translate("dependency.dialog.fetch"))
    private val autoDetectButton = JButton(translate("dependency.dialog.autoDetect"))
    private val fetchingIcon = AsyncProcessIcon("DependencyDialogFetch")
    private val fetchStatusLabel = JBLabel("").also { it.foreground = JBColor.RED }

    private lateinit var perVersionTableModel: PerVersionTableModel
    private lateinit var perVersionTable: JTable

    private var disposed = false

    override fun dispose() {
        disposed = true
        super.dispose()
    }

    init {
        title = if (existingEntry == null) translate("dependency.dialog.title.add") else translate("dependency.dialog.title.edit")
        init()

        perVersionTableModel.addTableModelListener { initValidation() }

        when (val spec = existingEntry?.versionSpec) {
            is DependencyVersionSpec.Single -> {
                singleVersionCombo.addItem(spec.version)
                singleVersionCombo.selectedItem = spec.version
            }
            else -> { /* per-version handled with the table */ }
        }

        fetchButton.addActionListener { fetchVersions() }
        perVersionFetchButton.addActionListener { fetchVersions() }
        autoDetectButton.addActionListener { fetchVersions(autoDetect = true) }
        addToFmjCheckbox.addActionListener { initValidation() }

        listOf(coordinateField, repoUrlField, modIdField).forEach { field ->
            field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = initValidation()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = initValidation()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = initValidation()
            })
        }

        fetchingIcon.isVisible = false
    }

    override fun createCenterPanel(): JComponent {
        perVersionTableModel = PerVersionTableModel(mcVersions, buildInitialPerVersionMap())
        perVersionTable = JBTable(perVersionTableModel).apply {
            setDefaultEditor(String::class.java, VersionComboEditor())
            rowHeight = 28
            preferredScrollableViewportSize = Dimension(500, mcVersions.size.coerceAtMost(8) * rowHeight)
        }

        val singleCard = JPanel(BorderLayout(4, 0)).apply {
            add(singleVersionCombo, BorderLayout.CENTER)
            val right = JPanel().apply {
                add(fetchButton)
            }
            add(right, BorderLayout.EAST)
        }

        val perVersionCard = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(perVersionTable), BorderLayout.CENTER)
            val bottom = JPanel().apply {
                add(perVersionFetchButton)
                add(autoDetectButton)
            }
            add(bottom, BorderLayout.SOUTH)
        }

        return panel {
            group(translate("dependency.dialog.metadata")) {
                row(translate("dependency.dialog.coordinate")) {
                    @Suppress("DialogTitleCapitalization")
                    cell(coordinateField).align(AlignX.FILL)
                        .comment(translate("dependency.dialog.coordinate.comment"))
                }
                row(translate("dependency.dialog.repoUrl")) {
                    @Suppress("DialogTitleCapitalization")
                    cell(repoUrlField).align(AlignX.FILL)
                        .comment(translate("dependency.dialog.repoUrl.comment"))
                }
                row {
                    cell(addToFmjCheckbox)
                    cell(remapCheckbox)
                    cell(includeCheckbox)
                }
                row(translate("dependency.dialog.modId")) {
                    cell(modIdField).align(AlignX.FILL)
                        .comment(translate("dependency.dialog.modId.comment"))
                }
            }

            group(translate("dependency.dialog.version")) {
                buttonsGroup {
                    row {
                        radioButton(translate("dependency.dialog.version.single"))
                            .applyToComponent {
                                isSelected = isSingleModeProperty.get()
                                addActionListener { isSingleModeProperty.set(true) }
                            }

                        radioButton(translate("dependency.dialog.version.separate"))
                            .applyToComponent {
                                isSelected = !isSingleModeProperty.get()
                                addActionListener { isSingleModeProperty.set(false) }
                            }
                    }
                }

                row {
                    cell(singleCard)
                        .visibleIf(isSingleModeProperty)
                        .align(AlignX.FILL)

                    cell(perVersionCard)
                        .visibleIf(isSingleModeProperty.not())
                        .align(AlignX.FILL)
                }
                row {
                    cell(fetchingIcon)
                    cell(fetchStatusLabel)
                }
            }
        }.also {
            it.preferredSize = Dimension(620, -1)
        }
    }

    private fun buildInitialPerVersionMap(): Map<String, String> =
        (existingEntry?.versionSpec as? DependencyVersionSpec.Multi)?.versions
            ?: mcVersions.associateWith { "" }

    private fun repositories(): List<String> =
        repoUrlField.text.split(',').map { it.trim().trimEnd('/') }.filter(String::isNotEmpty).distinct()

    private fun validRepo(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme in listOf("https", "http") && uri.host != null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null
    }

    private fun fetchVersions(autoDetect: Boolean = false) {
        val coordinate = coordinateField.text.trim()
        val parts = MavenVersions.splitCoordinate(coordinate)
        if (parts == null) {
            fetchStatusLabel.text = translate("dependency.error.invalidCoordinate")
            return
        }

        val (groupId, artifactId) = parts
        val configuredRepos = repositories()
        if (configuredRepos.any { !validRepo(it) }) {
            fetchStatusLabel.text = translate("dependency.error.repoUrlProtocol")
            return
        }

        if (perVersionTable.isEditing && !perVersionTable.cellEditor.stopCellEditing()) return

        val single = isSingleModeProperty.get()
        val previousSingle = singleVersion()
        val effectiveRepos = configuredRepos.ifEmpty {
            listOf("https://repo.maven.apache.org/maven2")
        }

        fetchStatusLabel.text = ""
        val buttons = listOf(fetchButton, perVersionFetchButton, autoDetectButton)
        buttons.forEach { it.isEnabled = false }
        fetchingIcon.isVisible = true
        fetchingIcon.resume()

        ApplicationManager.getApplication().executeOnPooledThread {
            val versions = effectiveRepos
                .flatMap { MavenVersions.fetchVersions(it, groupId, artifactId) }
                .distinct()

            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater

                fetchingIcon.suspend()
                fetchingIcon.isVisible = false
                buttons.forEach { it.isEnabled = true }

                if (
                    coordinate != coordinateField.text.trim() ||
                    configuredRepos != repositories() ||
                    single != isSingleModeProperty.get()
                ) return@invokeLater

                if (single) {
                    val previous = singleVersion().ifBlank { previousSingle }
                    singleVersionCombo.removeAllItems()
                    versions.forEach(singleVersionCombo::addItem)
                    if (previous.isNotBlank()) {
                        singleVersionCombo.editor.item = previous
                    }
                } else {
                    perVersionTableModel.setAvailableVersions(mcVersions.associateWith { versions })
                    if (autoDetect && versions.isNotEmpty()) {
                        val missing = perVersionTableModel.autoDetect()
                        if (missing.isNotEmpty()) {
                            fetchStatusLabel.text = translate(
                                "dependency.error.noAutoMatch",
                                params = arrayOf(missing.joinToString())
                            )
                        }
                    }
                }

                if (versions.isEmpty()) {
                    fetchStatusLabel.text = translate("dependency.error.noVersions")
                }

                initValidation()
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (MavenVersions.splitCoordinate(coordinateField.text.trim()) == null)
            return ValidationInfo(translate("dependency.error.invalidCoordinate"), coordinateField)
        if (repositories().any { !validRepo(it) })
            return ValidationInfo(translate("dependency.error.repoUrlProtocol"), repoUrlField)

        if (!modIdField.text.trim().matches(Regex("[a-z][a-z0-9_-]*")))
            return ValidationInfo(translate("dependency.error.invalidId"), modIdField)

        val versions = if (isSingleModeProperty.get()) listOf(singleVersion())
            else perVersionTableModel.getVersionMap().values
        if (versions.isEmpty() || versions.any { !it.matches(Regex("[A-Za-z0-9_.+~-]+")) })
            return ValidationInfo(translate("dependency.error.versionRequired"),
                if (isSingleModeProperty.get()) singleVersionCombo else perVersionTable)

        return null
    }

    override fun doOKAction() {
        if (perVersionTable.isEditing && !perVersionTable.cellEditor.stopCellEditing()) return
        super.doOKAction()
    }

    private fun singleVersion(): String = singleVersionCombo.editor.item?.toString().orEmpty().trim()

    fun getResult(): DependencyEntry {
        val versionSpec = if (isSingleModeProperty.get()) DependencyVersionSpec.Single(singleVersion())
            else DependencyVersionSpec.Multi(perVersionTableModel.getVersionMap())
        val repos = repositories()

        return DependencyEntry(
            modId = modIdField.text.trim(),
            addToFabricModJson = addToFmjCheckbox.isSelected,
            mavenCoordinate = coordinateField.text.trim(),
            mavenRepo = repos.firstOrNull().orEmpty(),
            versionSpec = versionSpec,
            additionalMavenRepos = repos.drop(1),
            remap = remapCheckbox.isSelected,
            includeInJar = includeCheckbox.isSelected,
        )
    }
}

private class PerVersionTableModel(private val mcVersions: List<String>, initialMap: Map<String, String>) : AbstractTableModel() {
    private val selected = LinkedHashMap<String, String>().apply { mcVersions.forEach { put(it, initialMap[it] ?: "") } }
    private var availableVersions: Map<String, List<String>> = emptyMap()

    fun setAvailableVersions(versions: Map<String, List<String>>) {
        availableVersions = versions
        fireTableDataChanged()
    }

    fun getVersionMap(): Map<String, String> = LinkedHashMap(selected)
    fun getAvailableVersions(row: Int): List<String> = availableVersions[mcVersions[row]].orEmpty()

    fun autoDetect(): List<String> {
        val missing = mutableListOf<String>()
        for (mcVersion in mcVersions) {
            val pattern = Regex("(?<![0-9.])${Regex.escape(mcVersion)}(?![0-9]|[.][0-9])", RegexOption.IGNORE_CASE)
            val match = availableVersions[mcVersion].orEmpty().firstOrNull { pattern.containsMatchIn(it) }
            if (match == null) missing += mcVersion
            else selected[mcVersion] = match
        }
        fireTableDataChanged()
        return missing
    }

    override fun getRowCount() = mcVersions.size
    override fun getColumnCount() = 2
    override fun getColumnName(column: Int) = if (column == 0) translate("mcVersion") else translate("dependency.dialog.version")
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
    private val combo = ComboBox<String>().apply { isEditable = true }

    override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): java.awt.Component {
        val model = table.model as? PerVersionTableModel
        combo.removeAllItems()
        combo.addItem("")
        model?.getAvailableVersions(table.convertRowIndexToModel(row))?.forEach { combo.addItem(it) }
        combo.editor.item = value?.toString().orEmpty()
        return combo
    }

    override fun getCellEditorValue(): Any = combo.editor.item?.toString().orEmpty().trim()
}
