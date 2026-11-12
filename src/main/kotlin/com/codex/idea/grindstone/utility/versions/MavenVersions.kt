package com.codex.idea.grindstone.utility.versions

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import kotlinx.serialization.cbor.CborTag.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.cancellation.CancellationException

object MavenVersions {
    private val log = logger<MavenVersions>()

    private val cloudsmithMavenPath = Regex("/public/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)/maven/?")
    private const val CLOUDSMITH_PAGE_SIZE = 100

    fun fetchVersions(repoUrl: String, groupId: String, artifactId: String): List<String> {
        val base = repoUrl.trim().trimEnd('/')
        val groupPath = groupId.replace('.', '/')
        val metadataUrl = "$base/$groupPath/$artifactId/maven-metadata.xml"

        val metadataVersions = try {
            parseVersions(HttpRequests.request(metadataUrl).readString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to fetch Maven metadata from $metadataUrl", e)
            emptyList()
        }

        val repo = runCatching { URI(base) }.getOrNull()
        if (repo?.host?.equals("dl.cloudsmith.io", ignoreCase = true) != true) {
            return metadataVersions
        }

        val match = cloudsmithMavenPath.matchEntire(repo.path.orEmpty())
            ?: return metadataVersions
        val (owner, repository) = match.destructured

        val cloudsmithVersions = try {
            fetchCloudsmithVersions(owner, repository, groupId, artifactId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to list Cloudsmith packages in $owner/$repository", e)
            emptyList()
        }

        return (cloudsmithVersions + metadataVersions).distinct()
    }

    private fun fetchCloudsmithVersions(owner: String, repository: String, groupId: String, artifactId: String, ): List<String> {
        val query = "format:maven maven_group_id:$groupId name:^$artifactId$"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val endpoint = "https://api.cloudsmith.io/packages/$owner/$repository/"

        val versions = linkedSetOf<String>()
        var page = 1

        while (true) {
            val url = "$endpoint?query=$encodedQuery&page_size=$CLOUDSMITH_PAGE_SIZE&page=$page"
            val (body, pageTotal) = HttpRequests.request(url).connect { request ->
                val total = request.connection
                    .getHeaderField("X-Pagination-PageTotal")
                    ?.toIntOrNull()
                request.readString() to total
            }

            val packages = Json.parseToJsonElement(body).jsonArray
            for (entry in packages) {
                val pkg = entry.jsonObject
                if (pkg["is_downloadable"]?.jsonPrimitive?.booleanOrNull == false) continue

                val version = pkg["version"]?.jsonPrimitive?.contentOrNull
                if (!version.isNullOrBlank()) versions.add(version)
            }

            if (packages.isEmpty() ||
                (pageTotal != null && page >= pageTotal) ||
                (pageTotal == null && packages.size < CLOUDSMITH_PAGE_SIZE)
            ) break

            page++
        }

        return versions.toList()
    }

    private fun parseVersions(xml: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }

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
        if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")) || it.contains("..") }) return null
        return parts[0] to parts[1]
    }
}
