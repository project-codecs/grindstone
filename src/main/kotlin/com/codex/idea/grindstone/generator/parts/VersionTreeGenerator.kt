package com.codex.idea.grindstone.generator.parts

import com.codex.idea.grindstone.generator.model.DependencyEntry
import com.codex.idea.grindstone.utility.baseDirectory
import com.codex.idea.grindstone.utility.createDirs
import com.codex.idea.grindstone.wizard.step.GrindstoneConfig
import com.intellij.openapi.project.Project

class VersionTreeGenerator(project: Project, var config: GrindstoneConfig) {
    var root = project.baseDirectory()!!

    fun sprout() {
        val versions = root.createDirs("versions")

        for (version in config.selectedVersions) {
            val dir = versions.createDirs(version)
            var resources = dir

            if (config.generateVersionedResources || config.accessWidenerType.perVersion) resources = dir.createDirs("src/main/resources")

            if (config.accessWidenerType.perVersion) resources.createChildData(this, "$version.${config.accessWidenerType.extension}")
                .setBinaryContent(config.accessWidenerType.header.toByteArray())

            dir.createChildData(this, "gradle.properties").setBinaryContent(propertiesFor(version).toByteArray())
        }
    }

    private fun propertiesFor(version: String): String {
        val b = StringBuilder()
        var shouldAppendLine = false

        config.modDependencies.filter(DependencyEntry::isMulti).forEach { dep ->
            b.appendLine("deps.${dep.modId}=${dep.versionFor(version)}")
            shouldAppendLine = true
        }

        if (shouldAppendLine) { b.appendLine(); shouldAppendLine = false }

        if (config.mappingsType == "yarn") {
            b.appendLine("deps.yarn=${config.yarnVersions[version] ?: "UNKNOWN-CHANGE-ME"}")
            shouldAppendLine = true
        }

        config.devDependencies.filter(DependencyEntry::isMulti).forEach { dep ->
            b.appendLine("r.deps.${dep.modId}=${dep.versionFor(version)}")
            shouldAppendLine = true
        }

        if (shouldAppendLine) b.appendLine()

        if (config.addTreeComments) b.appendLine("# Minecraft version(s) the mod can be installed in, used in fabric.mod.json. Multiple statements are valid (e.g. '>=1.21.6 <=1.21.8')")
        b.appendLine("mod.mc_dep=>=$version")

        if (config.accessWidenerType.perVersion) {
            b.appendLine("# Has to match the name of the folder it is contained in (or at least the name of the access file)")
            b.appendLine("mod.mc_title=$version")
        }

        if (config.generatePublishing) {
            b.appendLine("# Used for modrinth publishing. Has to be a list of supported versions separated by spaces. (e.g. '1.21.6 1.21.7 1.21.8')")
            b.appendLine("mod.mc_targets=$version")
        }

        return b.toString()
    }
}
