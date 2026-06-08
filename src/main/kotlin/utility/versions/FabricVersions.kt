package com.codex.idea.grindstone.utility.versions

import com.codex.idea.grindstone.wizard.step.FabricLoaderVersion
import com.codex.idea.grindstone.wizard.step.McGameVersion
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests

private const val FABRIC_META_URL = "https://meta.fabricmc.net/v2/versions"

object MinecraftVersions {
    private val MAX_VERSION = McVersion(1, 21, 11)

    @Volatile private var cached: List<McGameVersion>? = null
    private val log = logger<MinecraftVersions>()
    private val gson = Gson()
    private val listType = object : TypeToken<List<McGameVersion>>() {}.type

    val all: List<McGameVersion> get() = cached?.filter { McVersion.parse(it.version) <= MAX_VERSION } ?: emptyList()

    fun downloadData(yarn: Boolean = true): List<McGameVersion> {
        cached?.let { return if (yarn) all else it }
        return runCatching {
            val raw = HttpRequests.request("$FABRIC_META_URL/game").readString()
            val versions = gson.fromJson<List<McGameVersion>>(raw, listType)
            cached = versions
            return if (yarn) all else versions
        }.getOrElse {
            log.warn("Failed to fetch MC versions from Fabric meta", it)
            emptyList()
        }
    }
}

object LoaderVersions {
    private const val FABRIC_META_URL = "https://meta.fabricmc.net/v2/versions/loader"

    @Volatile private var cached: List<FabricLoaderVersion>? = null
    private val log = logger<LoaderVersions>()
    private val gson = Gson()
    private val listType = object : TypeToken<List<FabricLoaderVersion>>() {}.type

    fun downloadData(): List<FabricLoaderVersion> {
        cached?.let { return it }

        return runCatching {
            val raw = HttpRequests.request(FABRIC_META_URL).readString()
            val parsed: List<FabricLoaderVersion> = gson.fromJson(raw, listType)

            cached = parsed
                .sortedByDescending { SemVer.parse(it.version) }

            cached!!
        }.getOrElse {
            log.warn("Failed to fetch Fabric loader versions", it)
            emptyList()
        }
    }

    fun all(): List<FabricLoaderVersion> = cached ?: emptyList()
}

data class McVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<McVersion> {
    override fun compareTo(other: McVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    companion object {
        fun parse(raw: String): McVersion {
            val parts = raw.split(".").map { it.toIntOrNull() ?: 0 }
            return McVersion(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
        }
    }
}

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    companion object {
        fun parse(raw: String): SemVer {
            val parts = raw.split(".")
            return SemVer(
                parts.getOrNull(0)?.toIntOrNull() ?: 0,
                parts.getOrNull(1)?.toIntOrNull() ?: 0,
                parts.getOrNull(2)?.toIntOrNull() ?: 0
            )
        }
    }
}