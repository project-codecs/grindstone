package com.codex.idea.grindstone.generator.model

import kotlinx.serialization.Serializable

@Serializable
sealed class DependencyVersionSpec {
    @Serializable
    data class Single(val version: String) : DependencyVersionSpec()

    @Serializable
    data class Multi(val versions: Map<String, String>) : DependencyVersionSpec() // mc -> dep
}

@Serializable
data class DependencyEntry(
    val modId: String,
    val addToFabricModJson: Boolean,
    val mavenCoordinate: String,
    val mavenRepo: String,
    val versionSpec: DependencyVersionSpec,
    val additionalMavenRepos: List<String> = emptyList(),
    val remap: Boolean = true,
    val includeInJar: Boolean = false,
) {
    val resourceVersionKey: String
        get() = "dep_" + modId.replace("_", "_u").replace("-", "_h")

    fun repositories(): List<String> = (listOf(mavenRepo) + additionalMavenRepos)
        .map { it.trim().trimEnd('/') }
        .filter(String::isNotEmpty)
        .distinct()

    fun versionFor(mcVersion: String): String {
        return when(versionSpec) {
            is DependencyVersionSpec.Single -> versionSpec.version
            is DependencyVersionSpec.Multi -> versionSpec.versions[mcVersion] ?: ""
        }
    }

    fun version(): String {
        return when (versionSpec) {
            is DependencyVersionSpec.Single -> versionSpec.version
            is DependencyVersionSpec.Multi -> error("Trying to set global version property for a dependency that is per-versioned! Don't do that!")
        }
    }

    fun isSingle(): Boolean {
        return versionSpec is DependencyVersionSpec.Single
    }

    fun isMulti(): Boolean {
        return versionSpec is DependencyVersionSpec.Multi
    }
}
