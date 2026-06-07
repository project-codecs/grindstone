package com.codex.idea.grindstone.utility

import java.util.Locale

/**
 * Stolen from MinecraftDev by demonwav/EarthComputer
 */
fun String.toPackageName(): String {
    if (this.isEmpty()) {
        return "_"
    }

    val firstChar = this.first().let {
        if (it.isJavaIdentifierStart()) {
            "$it"
        } else {
            ""
        }
    }
    val packageName = firstChar + this.asSequence()
        .drop(1)
        .filter { it.isJavaIdentifierPart() || it == '.' }
        .joinToString("")

    return if (packageName.isEmpty()) { "_" } else {
        packageName.lowercase(Locale.ROOT)
    }
}