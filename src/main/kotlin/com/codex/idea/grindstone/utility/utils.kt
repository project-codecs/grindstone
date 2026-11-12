package com.codex.idea.grindstone.utility

import com.intellij.DynamicBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.PropertyKey
import java.io.File
import java.util.Locale

private const val BUNDLE = "messages.Grindstone"
object GrindstoneBundle : DynamicBundle(BUNDLE) {
    operator fun invoke(@PropertyKey(resourceBundle = BUNDLE) key: String): String {
        return getMessage(key)
    }

    operator fun invoke(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): String {
        return getMessage(key, *params)
    }
}

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

fun translate(key: String, before: Int = 0, after: Int = 0, vararg params: Any?): String {
    val message = if (params.isEmpty()) GrindstoneBundle(key) else GrindstoneBundle(key, *params)
    if (before + after == 0) return message
    val b = StringBuilder()
    repeat(before) { b.append(" ") }
    b.append(message)
    repeat(after) { b.append(" ") }
    return b.toString()
}