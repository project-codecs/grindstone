package com.codex.idea.grindstone.wizard.component

import com.codex.idea.grindstone.utility.translate
import com.codex.idea.grindstone.wizard.step.McGameVersion
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class McVersionPickerPanel(private val onSelectionChanged: (List<String>) -> Unit) : JPanel(BorderLayout(0, 4)) {
    private val searchField = JBTextField().apply { emptyText.text = translate("mcVersionPicker.search") }
    private val showSnapshotsBox = JBCheckBox(translate("mcVersionPicker.showSnapshots"), false)

    private val checkboxes = mutableListOf<JBCheckBox>()
    private val selected = linkedSetOf<String>()
    private var allVersions: List<McGameVersion> = emptyList()

    private val gridPanel = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))

    init {
        val top = JPanel(BorderLayout(4, 0))
        top.add(searchField, BorderLayout.CENTER)
        top.add(showSnapshotsBox, BorderLayout.EAST)
        add(top, BorderLayout.NORTH)

        val scroll = ScrollPaneFactory.createScrollPane(gridPanel, true)
        scroll.preferredSize = Dimension(400, 180)
        scroll.horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        add(scroll, BorderLayout.CENTER)

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = rebuildGrid()
            override fun removeUpdate(e: DocumentEvent?) = rebuildGrid()
            override fun changedUpdate(e: DocumentEvent?) = rebuildGrid()
        })
        showSnapshotsBox.addActionListener { rebuildGrid() }
    }

    fun setVersions(versions: List<McGameVersion>) {
        allVersions = versions
        rebuildGrid()
        onSelectionChanged(selected.toList())
    }

    private fun rebuildGrid() {
        val filter = searchField.text.trim()
        val showSnapshots = showSnapshotsBox.isSelected

        val filtered = allVersions.filter { v ->
            (showSnapshots || v.stable) &&
                    (filter.isEmpty() || v.version.contains(filter, ignoreCase = true))
        }

        checkboxes.clear()
        gridPanel.removeAll()

        for (v in filtered) {
            val cb = JBCheckBox(v.version, v.version in selected)
            cb.addActionListener {
                if (cb.isSelected) selected.add(v.version) else selected.remove(v.version)
                onSelectionChanged(selected.toList())
            }
            checkboxes.add(cb)
            gridPanel.add(cb)
        }

        gridPanel.revalidate()
        gridPanel.repaint()
    }
}

/* Based on Rob Camick's WrapLayout. */
private class WrapLayout(align: Int, hGap: Int, vGap: Int) : FlowLayout(align, hGap, vGap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)
    override fun minimumLayoutSize(target: Container): Dimension = layoutSize(target, false)

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            var targetWidth = target.size.width
            val container = if (target.parent?.parent != null) target.parent.parent else target
            if (targetWidth == 0) targetWidth = container.size.width

            val hGap = hgap
            val vGap = vgap

            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + hGap * 2
            val maxWidth = targetWidth - horizontalInsetsAndGap

            var height = vGap
            var rowWidth = 0
            var rowHeight = 0

            for (i in 0 until target.componentCount) {
                val c: Component = target.getComponent(i)
                if (!c.isVisible) continue
                val d = if (preferred) c.preferredSize else c.minimumSize
                if (rowWidth + d.width > maxWidth) {
                    height += rowHeight + vGap
                    rowWidth = 0
                    rowHeight = 0
                }
                rowWidth += d.width + hGap
                rowHeight = maxOf(rowHeight, d.height)
            }
            height += rowHeight + vGap + insets.top + insets.bottom
            return Dimension(maxWidth, height)
        }
    }
}