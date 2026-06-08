package com.codex.idea.grindstone.utility

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

class FileBuilder(private val file: VirtualFile) {
    private val builder = StringBuilder()

    fun append(text: String, line: Boolean = true): FileBuilder = apply {
        if (line) builder.appendLine(text) else builder.append(text)
    }

    fun append(text: String, line: Boolean = true, should: () -> Boolean): FileBuilder = apply {
        if (should()) {
            if (line) builder.appendLine(text) else builder.append(text)
        }
    }

    fun blank(lines: Int = 1): FileBuilder = apply {
        repeat(lines) { builder.appendLine() }
    }

    fun blank(lines: Int = 1, should: () -> Boolean): FileBuilder = apply {
        if (should()) repeat(lines) { builder.appendLine() }
    }

    fun write() {
        ApplicationManager.getApplication().runWriteAction { file.setBinaryContent(builder.toString().toByteArray(StandardCharsets.UTF_8)) }
    }
}