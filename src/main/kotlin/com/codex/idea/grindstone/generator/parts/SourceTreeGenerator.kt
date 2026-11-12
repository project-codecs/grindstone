package com.codex.idea.grindstone.generator.parts

import com.codex.idea.grindstone.utility.baseDirectory
import com.codex.idea.grindstone.utility.createDirs
import com.codex.idea.grindstone.wizard.step.AWType
import com.codex.idea.grindstone.wizard.step.GrindstoneConfig
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

class SourceTreeGenerator(project: Project, var config: GrindstoneConfig) {
    var root: VirtualFile = project.baseDirectory()!!

    fun sprout() {
        val entrypointClass = config.mainClass.substringAfterLast('.')
        val groupId = config.groupId
        val groupPath = groupId.replace('.', '/') + "/${config.modId}"

        val mainPackagePath = if (config.useSeparatePackges) "$groupPath/common" else groupPath
        val clientPackagePath = "$groupPath/client"
        val dataPackagePath = if (config.useSeparatePackges) "$groupPath/common/data" else groupPath

        val mainJava = root.createDirs("src/main/java")

        mainJava.createDirs(mainPackagePath).createChildData(this, "$entrypointClass.java")
            .setBinaryContent(mainClassContent(
                packageName = mainPackagePath.replace('/', '.'),
                className = entrypointClass,
                modId = config.modId
            ).toByteArray())

        mainJava.createDirs(clientPackagePath).createChildData(this, "${entrypointClass}Client.java")
            .setBinaryContent(clientClassContent(
                packageName = clientPackagePath.replace('/', '.'),
                className = "${entrypointClass}Client"
            ).toByteArray())

        if (config.useDatagen) {
            mainJava.createDirs(dataPackagePath).createChildData(this, "${entrypointClass}DataGenerator.java")
                .setBinaryContent(datagenClassContent(
                    packageName = dataPackagePath.replace('/', '.'),
                    className = "${entrypointClass}DataGenerator"
                ).toByteArray())
        }

        if (config.useMixins) mainJava.createDirs("$groupPath/mixin")
        val mainResources = root.createDirs("src/main/resources")

        if (config.accessWidenerType.shared)
            mainResources.createChildData(this, "${config.modId}.${config.accessWidenerType.extension}")
                .setBinaryContent(config.accessWidenerType.header.toByteArray())

        if (config.generateTests) {
            root.createDirs("src/test/java")
            root.createDirs("src/test/resources")
        }

        mainResources.createChildData(this, "fabric.mod.json")
            .setBinaryContent(fabricModJson(
                "${mainPackagePath.replace('/', '.')}.$entrypointClass",
                "${clientPackagePath.replace('/', '.')}.${entrypointClass}Client",
                "${dataPackagePath.replace('/', '.')}.${entrypointClass}DataGenerator"
            ).toByteArray())

        if (config.useMixins) mainResources.createChildData(this, "${config.modId}.mixins.json")
            .setBinaryContent(mixinJson(groupPath.replace('/', '.')).toByteArray())

        VfsUtil.markDirtyAndRefresh(false, true, true, root)
    }

    private fun mainClassContent(packageName: String, className: String, modId: String) = """
        package $packageName;
        
        import net.fabricmc.api.ModInitializer;
        import org.slf4j.Logger;
        import org.slf4j.LoggerFactory;
        
        public class $className implements ModInitializer {
            public static final String MOD_ID = "$modId";
            public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
        
            @Override
            public void onInitialize() {
                LOGGER.info("Initializing $modId");
            }
        }
    """.trimIndent() + "\n"

    private fun clientClassContent(packageName: String, className: String) = """
        package $packageName;
        
        import net.fabricmc.api.ClientModInitializer;
        
        public class $className implements ClientModInitializer {
            @Override
            public void onInitializeClient() {
                
            }
        }
    """.trimIndent() + "\n"

    private fun datagenClassContent(packageName: String, className: String) = """
        package $packageName;
        
        import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
        import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
        
        public class $className implements DataGeneratorEntrypoint {
            @Override
            public void onInitializeDataGenerator(FabricDataGenerator generator) {
                FabricDataGenerator.Pack pack = generator.createPack();
            }
        }
    """.trimIndent() + "\n"

    private fun fabricModJson(mainClass: String, clientClass: String, datagenClass: String) = """
        {
          "schemaVersion": 1,
          "id": "${'$'}{id}",
          "version": "${'$'}{version}",
          "name": "${'$'}{name}",
          "description": "CHANGE-ME",
          "authors": [],
          "contact": {},
          "license": "${config.license.id}",
          "icon": "assets/${config.modId}/icon.png",
          "environment": "*",${if (config.accessWidenerType != AWType.NONE) "\n          \"accessWidener\": \"\${aw}.${config.accessWidenerType.extension}\"," else ""}
          "entrypoints": {
            "main": [ "$mainClass" ],
            "client": [ "$clientClass" ]${if (config.useDatagen) ",\n            \"fabric-datagen\": [ \"$datagenClass\" ]" else ""}
          },
          ${if (config.useMixins) "\"mixins\": [ \"${config.modId}.mixins.json\" ],\n" else ""}
          "depends": {
            "minecraft": "${'$'}{minecraft}"${if (config.accessWidenerType.classTweaker) ",\n            \"fabricloader\": \">=0.18.0\"" else ""}${buildDepends()}
          }
        }
    """.trimIndent()

    private fun mixinJson(mainJava: String) = """
        {
          "required": true,
          "minVersion": "0.8",
          "package": "$mainJava.mixin",
          "compatibilityLevel": "${'$'}{java}",
          "mixins": [
          ],
          "client": [
          ],
          "injectors": {
            "defaultRequire": 1
          },
          "overwrites": {
            "requireAnnotations": true
          }
        }

    """.trimIndent()

    private fun buildDepends(): String {
        val deps = config.modDependencies.filter { it.addToFabricModJson }
        if (deps.isEmpty()) return ""

        val b = StringBuilder()
        for (dependency in deps) {
            b.append(",\n            \"${dependency.modId}\": \">=\${${dependency.resourceVersionKey}}\"")
        }
        return b.toString()
    }
}
