package com.codex.idea.grindstone.utility

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
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

fun Project.baseDirectory(): VirtualFile? {
    return basePath?.let { path -> VfsUtil.findFileByIoFile(File(path), true) }
}

fun VirtualFile.createDirs(relativePath: String): VirtualFile {
    return relativePath.split('/').fold(this) { parent, segment ->
        parent.findChild(segment) ?: parent.createChildDirectory(this, segment)
    }
}