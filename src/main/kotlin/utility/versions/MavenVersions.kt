package com.codex.idea.grindstone.utility.versions

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

object MavenVersions {
    private val log = logger<MavenVersions>()

    fun fetchVersions(repoUrl: String, groupId: String, artifactId: String): List<String> {
        val base = repoUrl.trimEnd('/')
        val groupPath = groupId.replace('.', '/')
        val metadataUrl = "$base/$groupPath/$artifactId/maven-metadata.xml"

        return runCatching {
            val xml = HttpRequests.request(metadataUrl).readString()
            parseVersions(xml)
        }.getOrElse {
            log.warn("Failed to fetch Maven metadata from $metadataUrl", it)
            emptyList()
        }
    }

    private fun parseVersions(xml: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xml.byteInputStream())
        doc.documentElement.normalize()

        val versions = mutableListOf<String>()
        val nodes = doc.getElementsByTagName("version")

        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val text = el.textContent.trim()
            if (text.isNotEmpty()) versions += text
        }

        return versions.reversed()
    }

    fun splitCoordinate(coordinate: String): Pair<String, String>? {
        val parts = coordinate.split(":")
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }
}