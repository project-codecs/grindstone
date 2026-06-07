package com.codex.idea.grindstone.assets

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

private fun icon(file: String?): Icon {
    return IconLoader.getIcon("assets/icons/$file.svg", Icons::class.java)
}

object Icons {
    @JvmField val STONECUTTER = icon("stonecutter")
}