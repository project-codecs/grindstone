package com.codex.idea.grindstone.generator.model

import kotlinx.serialization.Serializable

@Serializable
sealed class DependencyVersionSpec {
    @Serializable
    data class Single(val version: String) : DependencyVersionSpec()

    @Serializable
    data class PerVersion(val versions: Map<String, String>) : DependencyVersionSpec() // mc -> dep
}

data class DependencyEntry(
    val modId: String,
    val addToFabricModJson: Boolean,
    val mavenCoordinate: String,
    val mavenRepo: String,
    val versionSpec: DependencyVersionSpec
) {
    fun implementationPath(mcVersion: String): String {
        val v = when(versionSpec) {
            is DependencyVersionSpec.Single -> versionSpec.version
            is DependencyVersionSpec.PerVersion -> versionSpec.versions[mcVersion] ?: ""
        }
        return "$mavenCoordinate:$v"
    }
}