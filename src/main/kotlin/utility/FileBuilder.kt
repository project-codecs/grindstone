package com.codex.idea.grindstone.utility

import java.nio.file.Files
import java.nio.file.Path
import java.security.InvalidParameterException

@Suppress("unused")
class FileBuilder(path: Path) {
    val builder: StringBuilder

    init {
        if (Files.isDirectory(path)) throw InvalidParameterException("FileBuilder cannot point to a directory! Must pass a file path.")
        if (!Files.exists(path.parent)) Files.createDirectories(path)

        builder = StringBuilder()
    }

    fun append(what: String): FileBuilder {
        builder.append(what)
        return this
    }

    fun blank(lines: Int = 1): FileBuilder {
        for (i in 0 until lines) {
            builder.append("\n")
        }

        return this
    }
}