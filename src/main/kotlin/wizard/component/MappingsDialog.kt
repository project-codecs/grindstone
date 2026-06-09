package com.codex.idea.grindstone.wizard.component

import com.codex.idea.grindstone.utility.translate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.net.URL
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor

enum class MappingsType { OFFICIAL, YARN }

data class MappingsConfig(
    val type: MappingsType,
    val yarnVersions: Map<String, String> = emptyMap()
)

class MappingsDialog(private val mcVersions: List<String>, private val initial: MappingsConfig = MappingsConfig(MappingsType.OFFICIAL)) : DialogWrapper(true) {
    private val graph = PropertyGraph()
    private val isOfficialProperty = graph.property(initial.type == MappingsType.OFFICIAL)

    private lateinit var yarnTableModel: YarnVersionTableModel
    private lateinit var yarnTable: JTable

    private val fetchingIcon = AsyncProcessIcon("MappingsDialogFetch")
    private val statusLabel = JBLabel("").also { it.foreground = JBColor.RED }

    private val officialPanel = JPanel(CardLayout())
    private val yarnPanel = JPanel(CardLayout())

    companion object {
        private const val CARD_OFFICIAL = "official"
        private const val CARD_YARN = "yarn"

        fun fetchYarnBuilds(mcVersion: String): List<String> {
            return try {
                val url = URL("https://meta.fabricmc.net/v2/versions/yarn/$mcVersion")
                val text = url.readText()
                val versionRegex = Regex(""""version"\s*:\s*"([^"]+)"""")
                versionRegex.findAll(text).map { it.groupValues[1] }.toList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    init {
        title = translate("mappings.configure")
        init()

        fetchingIcon.isVisible = false
    }

    override fun createCenterPanel(): JComponent {
        yarnTableModel = YarnVersionTableModel(
            mcVersions = mcVersions,
            initialMap = initial.yarnVersions,
        )
        yarnTable = JBTable(yarnTableModel).apply {
            setDefaultEditor(String::class.java, YarnComboEditor())
            rowHeight = 28
            preferredScrollableViewportSize = Dimension(480, mcVersions.size.coerceAtMost(8) * rowHeight)
        }

        val officialCard = JPanel(BorderLayout()).apply {
            add(
                JBLabel(translate("mappings.official.description")),
                BorderLayout.NORTH
            )
        }

        val yarnCard = JPanel(BorderLayout(0, 6)).apply {
            add(JBScrollPane(yarnTable), BorderLayout.CENTER)

            val buttonRow = JPanel().apply {
                add(JButton(translate("mappings.fetchAutoDetect")).also { btn ->
                    btn.toolTipText = translate("mappings.fetchAutoDetect.tooltip")
                    btn.addActionListener { fetchAndAutoDetect() }
                })
                add(JButton(translate("mappings.reset")).also { btn ->
                    btn.toolTipText = translate("mappings.reset.tooltip")
                    btn.addActionListener { yarnTableModel.resetAll() }
                })
                add(fetchingIcon)
                add(statusLabel)
            }
            add(buttonRow, BorderLayout.SOUTH)
        }

        officialPanel.add(officialCard, CARD_OFFICIAL)
        yarnPanel.add(yarnCard, CARD_YARN)

        return panel {
            group(translate("mappings.type")) {
                buttonsGroup {
                    row {
                        radioButton(translate("mappings.official"))
                            .applyToComponent {
                                isSelected = isOfficialProperty.get()

                                addActionListener {
                                    isOfficialProperty.set(true)
                                }
                            }
                            .comment(translate("mappings.official.comment"))

                        radioButton(translate("mappings.yarn.label"))
                            .applyToComponent {
                                isSelected = !isOfficialProperty.get()

                                addActionListener {
                                    isOfficialProperty.set(false)
                                }
                            }
                            .comment(translate("mappings.yarn.comment"))
                    }
                }
            }

            group(translate("mappings.configuration")) {
                row {
                    cell(officialPanel)
                        .visibleIf(isOfficialProperty)
                        .align(AlignX.FILL)

                    cell(yarnPanel)
                        .visibleIf(isOfficialProperty.not())
                        .align(AlignX.FILL)
                }
            }
        }.also {
            it.preferredSize = Dimension(560, -1)
        }
    }

    private fun fetchAndAutoDetect() {
        statusLabel.text = ""
        fetchingIcon.isVisible = true
        fetchingIcon.resume()
        isOKActionEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val results = mutableMapOf<String, String>()
            var anyFailed = false

            for (mcVersion in mcVersions) {
                val builds = fetchYarnBuilds(mcVersion)
                if (builds.isNotEmpty()) {
                    results[mcVersion] = builds[0]
                    yarnTableModel.setAvailableVersions(mcVersion, builds)
                } else {
                    anyFailed = true
                    results[mcVersion] = ""
                }
            }

            SwingUtilities.invokeLater {
                fetchingIcon.suspend()
                fetchingIcon.isVisible = false

                yarnTableModel.applyAutoDetected(results)

                if (anyFailed) {
                    statusLabel.text = translate("mappings.error.fetchSome")
                } else {
                    statusLabel.text = ""
                }

                initValidation()
                isOKActionEnabled = doValidate() == null
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (!isOfficialProperty.get()) {
            val missing = mcVersions.filter { yarnTableModel.getVersionFor(it).isBlank() }
            if (missing.isNotEmpty()) {
                return ValidationInfo(
                    translate("mappings.error.missingFor", params = arrayOf(missing.joinToString(", "))),
                    null
                )
            }
        }
        return null
    }

    fun getResult(): MappingsConfig {
        return if (isOfficialProperty.get()) {
            MappingsConfig(type = MappingsType.OFFICIAL)
        } else {
            MappingsConfig(
                type = MappingsType.YARN,
                yarnVersions = yarnTableModel.getVersionMap(),
            )
        }
    }
}

private class YarnVersionTableModel(private val mcVersions: List<String>, initialMap: Map<String, String>) : AbstractTableModel() {
    private val selected = LinkedHashMap<String, String>().apply {
        mcVersions.forEach { put(it, initialMap[it] ?: "") }
    }

    private val available = LinkedHashMap<String, List<String>>().apply {
        mcVersions.forEach { put(it, emptyList()) }
    }

    fun setAvailableVersions(mcVersion: String, versions: List<String>) {
        available[mcVersion] = versions
        val row = mcVersions.indexOf(mcVersion)
        if (row >= 0) fireTableRowsUpdated(row, row)
    }

    fun applyAutoDetected(map: Map<String, String>) {
        map.forEach { (mc, yarn) -> selected[mc] = yarn }
        fireTableDataChanged()
    }

    fun resetAll() {
        mcVersions.forEach { selected[it] = "" }
        fireTableDataChanged()
    }

    fun getVersionFor(mcVersion: String): String = selected[mcVersion] ?: ""
    fun getVersionMap(): Map<String, String> = LinkedHashMap(selected)
    fun getAvailableFor(mcVersion: String): List<String> = available[mcVersion] ?: emptyList()

    override fun getRowCount() = mcVersions.size
    override fun getColumnCount() = 2
    override fun getColumnName(col: Int) = if (col == 0) translate("mcVersion") else translate("mappings.yarn.version")
    override fun getColumnClass(col: Int) = String::class.java
    override fun isCellEditable(row: Int, col: Int) = col == 1

    override fun getValueAt(row: Int, col: Int): Any {
        val mc = mcVersions[row]
        return if (col == 0) mc else selected[mc] ?: ""
    }

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        if (col != 1) return
        selected[mcVersions[row]] = value?.toString().orEmpty()
        fireTableCellUpdated(row, col)
    }
}


private class YarnComboEditor : AbstractCellEditor(), TableCellEditor {
    private val combo = ComboBox<String>()

    override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, col: Int): Component {
        val model = table.model as? YarnVersionTableModel
        val mcVersion = table.getValueAt(row, 0) as? String ?: ""
        combo.removeAllItems()
        combo.addItem("")
        model?.getAvailableFor(mcVersion)?.forEach { combo.addItem(it) }
        combo.selectedItem = value?.toString()?.takeIf { it.isNotEmpty() } ?: ""
        return combo
    }

    override fun getCellEditorValue(): Any = combo.selectedItem ?: ""
}