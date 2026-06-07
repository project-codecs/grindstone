package com.codex.idea.grindstone.utility.versions

import com.codex.idea.grindstone.wizard.step.McGameVersion
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests

object MinecraftVersions {
    private const val FABRIC_META_URL = "https://meta.fabricmc.net/v2/versions/game"
    private val MAX_VERSION = McVersion(1, 21, 11)

    @Volatile private var cached: List<McGameVersion>? = null
    private val log = logger<MinecraftVersions>()
    private val gson = Gson()
    private val listType = object : TypeToken<List<McGameVersion>>() {}.type

    val stable: List<McGameVersion> get() = cached?.filter { it.stable && McVersion.parse(it.version) <= MAX_VERSION } ?: emptyList()
    val all: List<McGameVersion> get() = cached?.filter { McVersion.parse(it.version) <= MAX_VERSION } ?: emptyList()

    fun downloadData(): List<McGameVersion> {
        cached?.let { return it }
        return runCatching {
            val raw = HttpRequests.request(FABRIC_META_URL).readString()
            val versions = gson.fromJson<List<McGameVersion>>(raw, listType)
            cached = versions
            all
        }.getOrElse {
            log.warn("Failed to fetch MC versions from Fabric meta", it)
            emptyList()
        }
    }
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
