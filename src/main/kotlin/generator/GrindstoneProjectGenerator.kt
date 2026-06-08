package com.codex.idea.grindstone.generator

import com.codex.idea.grindstone.generator.parts.GradleFileGenerator
import com.codex.idea.grindstone.generator.parts.SourceTreeGenerator
import com.codex.idea.grindstone.generator.parts.VersionTreeGenerator
import com.codex.idea.grindstone.utility.baseDirectory
import com.codex.idea.grindstone.wizard.step.GrindstoneConfig
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class GrindstoneProjectGenerator(var project: Project, var context: WizardContext, var config: GrindstoneConfig) {
    val staticFiles = listOf(
        "-gitattributes", ".gitattributes",
        "-gitignore", ".gitignore",
        "CHANGELOG.md", "",
        "README.md", "",
        "gradlew", "",
        "gradlew.bat", "",
        "gradle/wrapper/gradle-wrapper.properties", "",
        "gradle/wrapper/gradle-wrapper.jar", ""
    )

    fun generate() {
        copyFiles(project.baseDirectory()!!)

        ApplicationManager.getApplication().runWriteAction {
            GradleFileGenerator(project, config)
                .properties()
                .settings()
                .stonecutter()
                .build()

            VersionTreeGenerator(project, config).sprout()
            SourceTreeGenerator(project, config).sprout()
        }
    }

    private fun copyFiles(targetDir: VirtualFile) {
        require(staticFiles.size % 2 == 0) { "Files must be provided as pairs: (source, target)" }

        ApplicationManager.getApplication().runWriteAction {
            var i = 0
            while (i < staticFiles.size) {
                val sourceName = staticFiles[i]
                val targetName = staticFiles[i + 1].ifEmpty { sourceName }

                val resourcePath = "/templates/static/$sourceName"
                val stream = javaClass.getResourceAsStream(resourcePath) ?: error("Missing resource file: $resourcePath")
                val bytes = stream.use { it.readBytes() }

                createFile(targetName, bytes, targetDir)

                i += 2
            }
        }
    }

    private fun createFile(path: String, bytes: ByteArray, root: VirtualFile) {
        val parts = path.split('/')
        var dir = root

        for (part in parts.dropLast(1)) dir = dir.findChild(part) ?: dir.createChildDirectory(this, part)
        val file = dir.findChild(parts.last()) ?: dir.createChildData(this, parts.last())

        file.setBinaryContent(bytes)
    }
}