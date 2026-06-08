package com.codex.idea.grindstone.generator.model

import kotlinx.serialization.Serializable

@Serializable
sealed class DependencyVersionSpec {
    @Serializable
    data class Single(val version: String) : DependencyVersionSpec()

    @Serializable
    data class Multi(val versions: Map<String, String>) : DependencyVersionSpec() // mc -> dep
}

data class DependencyEntry(val modId: String, val addToFabricModJson: Boolean, val mavenCoordinate: String, val mavenRepo: String, val versionSpec: DependencyVersionSpec) {
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