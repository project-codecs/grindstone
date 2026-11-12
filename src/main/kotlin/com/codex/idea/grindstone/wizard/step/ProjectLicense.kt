package com.codex.idea.grindstone.wizard.step

import java.time.Year

enum class ProjectLicense(val id: String) {
    ALL_RIGHTS_RESERVED("All-Rights-Reserved"),
    MIT("MIT"),
    APACHE_2_0("Apache-2.0"),
    GPL_3_0_ONLY("GPL-3.0-only"),
    CC0_1_0("CC0-1.0");

    fun text(copyrightHolder: String): String {
        val resource = "/templates/licenses/$id.txt"
        val template = checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing resource: $resource" }
        val holder = copyrightHolder.ifBlank { "[copyright holder]" }
        return template.bufferedReader().use { it.readText() }
            .replace("[year]", Year.now().toString())
            .replace("[copyright holder]", holder)
    }

    companion object {
        fun validFileName(name: String): Boolean {
            if (name.isEmpty()) return true
            if (name == "." || name.endsWith('.') || name.any { it.isISOControl() || it in "<>:\"/\\|?*" }) return false
            if (name.substringBefore('.').matches(Regex("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))) return false
            return name.lowercase() !in setOf(
                ".git", ".gitattributes", ".gitignore", ".gradle", ".idea", "src", "versions", "gradle",
                "gradlew", "gradlew.bat", "readme.md", "changelog.md", "gradle.properties",
                "settings.gradle.kts", "build.gradle.kts", "stonecutter.gradle.kts",
            )
        }
    }
}
