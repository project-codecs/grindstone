package com.codex.idea.grindstone.generator.parts

import androidx.compose.ui.util.fastJoinToString
import com.codex.idea.grindstone.generator.model.DependencyEntry
import com.codex.idea.grindstone.generator.model.DependencyVersionSpec
import com.codex.idea.grindstone.utility.FileBuilder
import com.codex.idea.grindstone.utility.baseDirectory
import com.codex.idea.grindstone.wizard.step.AWType
import com.codex.idea.grindstone.wizard.step.GrindstoneConfig
import com.intellij.openapi.project.Project

class GradleFileGenerator(project: Project, var config: GrindstoneConfig) {
    var root = project.baseDirectory()!!

    fun properties(): GradleFileGenerator {
        val builder = FileBuilder(root.createChildData(this, "gradle.properties"))
            .append("org.gradle.jvmargs=-Xmx${config.maxMemoryUsage}m")
            .append("org.gradle.parallel=true")
            .append("org.gradle.configuration-cache=false")
            .blank()
            .append("mod.version=${config.version}")
            .append("mod.group=${config.groupId}")
            .append("mod.id=${config.modId}")
            .append("mod.archives_base_name=${config.artifactId}")
            .append("mod.name=${config.modName}")
            .blank()
            .append("deps.fabric_loader=${config.fabricLoaderVersion}")

        if (appendDependencyProps(builder, config.modDependencies.filter { entry -> entry.versionSpec is DependencyVersionSpec.Single }, ""  )) builder.blank()
        if (appendDependencyProps(builder, config.devDependencies.filter { entry -> entry.versionSpec is DependencyVersionSpec.Single }, "r.")) builder.blank()

        builder
            .append("publish.modrinth=CHANGE_ME")
            .blank() { config.generateTests }
            .append("junit_version=6.0.1") { config.generateTests }
            .write()

        return this
    }

    fun settings(): GradleFileGenerator {
        FileBuilder(root.createChildData(this, "settings.gradle.kts"))
            .append("""
                pluginManagement {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                        gradlePluginPortal()
                        maven("https://maven.fabricmc.net/")
                        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
                    }
                }

                plugins {
                    id("dev.kikugie.stonecutter") version "0.9.4"
                }
               
                stonecutter {
                    create(rootProject) {
                        versions(
            """.trimIndent(), false)
            .append("\"", false).append(config.selectedVersions.fastJoinToString("\", \""), false).append("\")")
            .append("        vcsVersion = \"${config.vcsVersion}\"")
            .append("    }")
            .append("}").blank()
            .append("rootProject.name = \"${config.modName}\"")
            .write()

        return this
    }

    fun stonecutter(): GradleFileGenerator {
        FileBuilder(root.createChildData(this, "stonecutter.gradle.kts"))
            .append("""
                plugins {
                    id("dev.kikugie.stonecutter")
                    id("net.fabricmc.fabric-loom-remap") version "${config.loomVersion}" apply false${if (config.generatePublishing) "\n                    id(\"me.modmuss50.mod-publish-plugin\") version \"1.0.+\" apply false" else ""}
                }

                stonecutter active "${config.vcsVersion}"

                stonecutter parameters {
                    constants["release"] = false // Enable when publishing to maven repositories, or your dependents will need your dev mods!
                    constants["legacy"] = current.parsed < "26"
                }
            """.trimIndent()).write()

        return this
    }

    fun build() {
        val builder = FileBuilder(root.createChildData(this, "build.gradle.kts"))
            .append("plugins {")
            .append("""    id("com.modrinth.minotaur") version "2+"""") { config.generatePublishing }
            .append("""    id("me.modmuss50.mod-publish-plugin")${'\n'}""") { config.generatePublishing }
            .append("""
                    id("net.fabricmc.fabric-loom-remap")
                    id("maven-publish")
                    id("java")
                }
            """.trimIndent())
            .blank()
            .append("""
                @Suppress("UnstableApiUsage")
                val buildNum: String =
                    providers.environmentVariable("GITHUB_RUN_NUMBER")
                        .filter { it.isNotBlank() }
                        .map { "build.${'$'}it" }
                        .orElse("local")
                        .get()
            """.trimIndent()) { config.useGithubBuildNumber }
            .blank() { config.useGithubBuildNumber }
            .append("""
                version = "${'$'}{property("mod.version")}+mc${'$'}{sc.current.version}"
                base.archivesName = property("mod.archives_base_name").toString()

                val requiredJava = when {
                    sc.current.parsed >= "26" -> JavaVersion.VERSION_25
                    sc.current.parsed >= "1.20.6" -> JavaVersion.VERSION_21
                    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
                    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
                    else -> JavaVersion.VERSION_1_8
                }

                fun env(key: String): String {
                    return providers.environmentVariable(key).orElse("").get()
                }

                fun present(key: String): Boolean {
                    return providers.environmentVariable(key).isPresent
                }
                
                repositories {
                    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
                        forRepository { maven(url) { name = alias } }
                        filter { groups.forEach(::includeGroup) }
                    }
                    
                ${buildRepositories()}
            """.trimIndent())
            .append("    mavenCentral()")
            .append("}")
            .blank()
            .append("""
                dependencies {
                    minecraft("com.mojang:minecraft:${'$'}{sc.current.version}")
            """.trimIndent())
            .append("""    mappings("net.fabricmc:yarn:${'$'}{property("deps.yarn")}:v2")""") { config.mappingsType == "yarn" }
            .append("""    mappings(loom.officialMojangMappings())""") { config.mappingsType == "official" }
            .blank()
            .append("""    modImplementation("net.fabricmc:fabric-loader:${'$'}{property("deps.fabric_loader")}")""")
            .blank() { config.modDependencies.isNotEmpty() || config.devDependencies.isNotEmpty() }
            .append("""    testImplementation(platform("org.junit:junit-bom:${'$'}{property("junit_version")}"))""") { config.generateTests }
            .append("""    testImplementation("org.junit.jupiter:junit-jupiter")""") { config.generateTests }
            .append("""    testRuntimeOnly("org.junit.platform:junit-platform-launcher")""") { config.generateTests }
            .blank() { config.generateTests }

        generateDependencies(builder, config.modDependencies, "modImplementation(", "")

        if (config.devDependencies.isNotEmpty()) {
            builder
                .append("    sc.constants[\"release\"]?.let {")
                .append("        if (!it) {")

            generateDependencies(builder, config.devDependencies, "modLocalRuntime(", "        ")

            builder
                .append("        }")
                .append("    }")
        }

        builder
            .append("}")
            .blank()
            .append("loom {")
            .append("""    accessWidenerPath = project.file("src/main/resources/${'$'}{property("mod.mc_title")}.accesswidener")""") { config.accessWidenerType == AWType.SPREAD }
            .append("""    accessWidenerPath = project.file("src/main/resources/${config.modId}.accesswidener")""") { config.accessWidenerType == AWType.SINGLE }
            .blank() { config.accessWidenerType != AWType.NONE }
            .append("""
                    decompilerOptions.named("vineflower") {
                        options.put("mark-corresponding-synthetics", "1")
                    }

                    runConfigs.all {
                        ideConfigGenerated(true)
                        runDir = "../../run"
                    }
                }
            """.trimIndent())
            .blank()
            .append("""
                fabricApi {
                    configureDataGeneration {
                        client = true
                    }
                }
            """.trimIndent()) { config.useDatagen }
            .blank() { config.useDatagen }
            .append("""
                java {
                    withSourcesJar()
                    withJavadocJar()
                    targetCompatibility = requiredJava
                    sourceCompatibility = requiredJava
                }
            """.trimIndent())
            .blank()
            .append("val canPublish = present(\"CLOUDSMITH_USERNAME\") && present(\"CLOUDSMITH_API_KEY\")") { config.generatePublishing }
            .append("""
                tasks {
                    processResources {
                        val props = mapOf(
                            "id" to project.property("mod.id"),
                            "name" to project.property("mod.name"),
                            "version" to project.property("mod.version"),
                            "minecraft" to project.property("mod.mc_dep")${buildPRMaps()}
                        )
                
                        props.forEach { (key, value) -> inputs.property(key, value) }
                
                        filesMatching("fabric.mod.json") { expand(props) }
            """.trimIndent())
            .append("""
                ${'\n'}
                        val mixinJava = "JAVA_${'$'}{requiredJava.majorVersion}"
                        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
                    }
            """.trimIndent()) { config.useMixins }
            .append("    }") { !config.useMixins }
            .blank()
            .append("""
                    register<Delete>("cleanArtifacts") {
                        delete(rootProject.file("artifacts"))
                    }
                
                    register<Copy>("buildAndCollect") {
                        group = "build"
                        from(remapJar.map { it.archiveFile }, remapSourcesJar.map { it.archiveFile })
                        into(rootProject.layout.buildDirectory.file("libs/${'$'}{project.property("mod.version")}"))
                        dependsOn("build")
                    }
                
                    register<Copy>("collectArtifacts") {
                        group = "build"
                        dependsOn("buildAndCollect")
                
                        from(remapJar.map { it.archiveFile })
                        into(rootProject.file("artifacts"))
                    }
            """.trimIndent().prependIndent("    ")) { config.useArtifactCollection }
            .append("""
                    test {
                        useJUnitPlatform()
                        testLogging {
                            events("passed", "skipped", "failed")
                        }
                    }
            """.trimIndent().prependIndent("    ")) { config.generateTests }
            .append("""
                ${'\n'}
                    withType<AbstractArchiveTask>().configureEach {
                        if (name == "remapJar" || name == "sourcesJar") {
                            archiveFileName.set(
                                "${'$'}{base.archivesName.get()}-" +
                                "${'$'}{project.property("mod.version")}+" +
                                "${if (config.useGithubBuildNumber) "\$buildNum-" else "" }mc${'$'}{sc.current.version}" +
                                if (name == "remapSourcesJar") "-sources.jar" else ".jar"
                            )
                        }
                    }
                
                    javadoc {
                        options {
                            (this as CoreJavadocOptions).addBooleanOption("Xdoclint:none", true)
                            this.addStringOption("quiet")
                        }
                    }
                }
            """.trimIndent())
            .append("""
                
                val changelogText = rootProject.file("CHANGELOG.md").readText()
                val regex = Regex(""${'"'}## \[\d+(?:\.\d+)*\] - \d{4}-\d{2}-\d{2}[\s\S]*?(?=\n## \[|$)""${'"'})
                val latestChange = regex.find(changelogText)?.value ?: "No changelog available"

                publishMods {
                    file = tasks.remapJar.map { it.archiveFile.get() }
                    displayName = "${'$'}{property("mod.version")} for ${'$'}{property("mod.mc_title")}"
                    version = "${'$'}{property("mod.version")}${if (config.useGithubBuildNumber) "-\$buildNum" else ""}+mc${'$'}{sc.current.version}"
                    changelog = latestChange
                    type = STABLE
                    modLoaders.add("fabric")

                    dryRun = !present("MODRINTH_TOKEN")

                    modrinth {
                        projectId = property("publish.modrinth") as String
                        accessToken = env("MODRINTH_TOKEN")
                        minecraftVersions.addAll(property("mod.mc_targets").toString().split(' '))

                //      TODO: Set up as needed.
                
                //        requires {
                //            slug = "fabric-api"
                //            version = project.property("deps.fabric_api").toString()
                //        }

                //        optional {
                //            slug = "modmenu"
                //            version = project.property("r.deps.mod_menu").toString()
                //        }
                    }
                }

                modrinth {
                    syncBodyFrom = rootProject.file("README.md").readText()
                    token = env("MODRINTH_TOKEN")
                    projectId = project.property("publish.modrinth").toString()
                }
            """.trimIndent()) { config.generatePublishing }
            .write()
    }

    private fun buildPRMaps(): String {
        val b = StringBuilder()

        config.modDependencies.forEach { dep -> b.append(",\n                            \"${dep.modId.replace('-', '_')}\" to project.property(\"deps.${dep.modId}\")") }
        if (config.accessWidenerType == AWType.SPREAD) b.appendLine(",\n                            \"aw\" to project.property(\"mod.mc_title\")")
        else if (config.accessWidenerType == AWType.SINGLE) b.append(",\n                            \"aw\" to \"${config.modId}\"")

        return b.toString()
    }

    private fun buildRepositories(): String {
        val b = StringBuilder()

        config.modDependencies.forEach { dep -> b.appendLine("    strictMaven(\"${dep.mavenRepo}\", \"\")\n") }
        config.devDependencies.forEach { dep -> b.appendLine("    strictMaven(\"${dep.mavenRepo}\", \"\")\n") }

        return b.toString()
    }

    private fun generateDependencies(builder: FileBuilder, deps: List<DependencyEntry>, prefix: String, indent: String) {
        for (dep in deps) builder.append("$indent    $prefix\"", false).append("${dep.mavenCoordinate}:\${property(\"deps.${dep.modId}\")}\")")
    }

    private fun appendDependencyProps(builder: FileBuilder, deps: List<DependencyEntry>, prefix: String = ""): Boolean {
        for (dep in deps) builder.append("${prefix}deps.${dep.modId}=${dep.version()}")

        return deps.isNotEmpty()
    }
}