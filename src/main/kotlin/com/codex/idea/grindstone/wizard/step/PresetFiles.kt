package com.codex.idea.grindstone.wizard.step

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object PresetFiles {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private const val FORMAT_VERSION = 1

    @Serializable
    private data class Preset(val formatVersion: Int, val configuration: GrindstoneConfig)

    fun open(path: Path): GrindstoneConfig {
        val preset = json.decodeFromString<Preset>(Files.readString(path))
        require(preset.formatVersion == FORMAT_VERSION) { "Unsupported preset format: ${preset.formatVersion}" }
        require(preset.configuration.mappingsType in setOf("official", "yarn")) { "Invalid mappings type" }
        return preset.configuration
    }

    fun save(path: Path, configuration: GrindstoneConfig) {
        val target = path.toAbsolutePath()
        val temporary = Files.createTempFile(target.parent, ".grindstone-", ".json")
        try {
            Files.writeString(temporary, json.encodeToString(Preset(FORMAT_VERSION, configuration)))
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
