package com.codex.idea.grindstone.wizard.step

import com.codex.idea.grindstone.generator.model.DependencyEntry
import com.codex.idea.grindstone.utility.License
import com.codex.idea.grindstone.utility.WizardUtils.findStep
import com.codex.idea.grindstone.utility.toPackageName
import com.codex.idea.grindstone.utility.versions.McVersion
import com.codex.idea.grindstone.utility.versions.MinecraftVersions
import com.codex.idea.grindstone.wizard.component.DependencyListDialog
import com.codex.idea.grindstone.wizard.panel.McVersionPickerPanel
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.util.bindStorage
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.validation.CHECK_NON_EMPTY
import com.intellij.openapi.ui.validation.WHEN_GRAPH_PROPAGATION_FINISHED
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.textValidation
import com.intellij.util.ui.AsyncProcessIcon
import kotlinx.serialization.Serializable
import javax.swing.DefaultComboBoxModel
import javax.swing.SwingUtilities

enum class AWType(val label: String) {
    SINGLE("One shared file in src/main/resources"),
    SPREAD("Separate access widener files for all versions"),
    NONE("None");
}

@Serializable
data class McGameVersion (
    val version: String,
    val stable: Boolean
)

@Serializable
data class GrindstoneConfig(
    val selectedVersions: List<String>,
    val vcsVersion: String,
    val fabricLoaderVersion: String,
    val modDependencies: List<DependencyEntry>,
    val devDependencies: List<DependencyEntry>,
    val fabricApiVersion: String,
    val accessWidenerType: AWType,
    val useMixins: Boolean,
    val generateVersionedResources: Boolean,
    val generatePublishing: Boolean,
)

class GrindstoneStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    private val baseData = data.getUserData(NewProjectWizardBaseData.KEY) ?: error("Step created without base data")

    private val versionsLoadingProperty = propertyGraph.property(true)
    private val noVersionsProperty = propertyGraph.property(false)

    val selectedVersionsProperty: GraphProperty<List<String>> = propertyGraph.property(emptyList())
    var selectedVersions by selectedVersionsProperty

    val vcsVersionProperty: GraphProperty<String> = propertyGraph.property("")
    var vcsVersion by vcsVersionProperty

    val loomVersionProperty: GraphProperty<String> = propertyGraph.property("1.16-SNAPSHOT")
    val loaderVersionProperty: GraphProperty<String> = propertyGraph.property("0.16.9")
    val fabricApiVersionProperty: GraphProperty<String> = propertyGraph.property("")

    val awTypeProperty: GraphProperty<AWType> = propertyGraph.property(AWType.SPREAD)

    val useMixinsProperty = propertyGraph.property(false)
    val useDatagenProperty = propertyGraph.property(false)
    val generateVersionedResourcesProperty = propertyGraph.property(false)
    val generatePublishingProperty = propertyGraph.property(false)
    val modIdProperty = propertyGraph.property("")
    val modNameProperty = propertyGraph.property("")
    val mainClassProperty = propertyGraph.lazyProperty(::suggestMainClassName)
    val licenseProperty = propertyGraph.property(License.ALL_RIGHTS_RESERVED.id)

    val groupIdProperty = propertyGraph.property("org.example").bindStorage("${javaClass.name}.groupId")
    val artifactIdProperty = propertyGraph.lazyProperty(modNameProperty::get)
    val versionProperty = propertyGraph.property("1.0-SNAPSHOT").bindStorage("${javaClass.name}.version")

    var groupId by groupIdProperty
    var artifactId by artifactIdProperty
    var version by versionProperty

    val descriptionProperty = propertyGraph.property("")
    val authorsProperty = propertyGraph.property("")
    val websiteProperty = propertyGraph.property("")

    val description by descriptionProperty
    val authors by authorsProperty
    val website by websiteProperty

    val modDependenciesProperty: GraphProperty<List<DependencyEntry>> = propertyGraph.property(emptyList())
    var modDependencies by modDependenciesProperty

    val devDependenciesProperty: GraphProperty<List<DependencyEntry>> = propertyGraph.property(emptyList())
    var devDependencies by devDependenciesProperty

    private lateinit var versionPicker: McVersionPickerPanel
    private val controllerComboModel = DefaultComboBoxModel<String>()

    init {
        selectedVersionsProperty.afterChange { versions ->
            val sorted = versions.sortedWith(compareByDescending { McVersion.parse(it) })
            controllerComboModel.removeAllElements()
            sorted.forEach { controllerComboModel.addElement(it) }
            if (vcsVersion !in sorted) vcsVersion = sorted.firstOrNull() ?: ""
        }

        artifactIdProperty.dependsOn(baseData.nameProperty, ::suggestArtifactId)
    }

    override fun setupUI(builder: Panel) {
        builder.row {
            cell(AsyncProcessIcon("StonecutterStep versions load"))
            label("Fetching Minecraft versions…")
        }.visibleIf(versionsLoadingProperty)

        builder.row {
            label("Could not load version list. Check your internet connection.")
                .apply { component.foreground = JBColor.RED }
        }.visibleIf(noVersionsProperty)

        builder.row("Minecraft versions") {
            versionPicker = McVersionPickerPanel { newSelection ->
                selectedVersions = newSelection
            }
            cell(versionPicker).align(AlignX.FILL)
        }.visibleIf(versionsLoadingProperty.not())

        builder.row("Active/VCS version") {
            comboBox(controllerComboModel)
                .bindItem(vcsVersionProperty)
            comment("The version Stonecutter treats as the VCS version and the current active project root")
        }.visibleIf(versionsLoadingProperty.not())

        builder.row("Mod ID:") {
            textField()
                .bindText(modIdProperty)
                .columns(COLUMNS_MEDIUM)
                .validationRequestor(WHEN_GRAPH_PROPAGATION_FINISHED(propertyGraph))
                .textValidation(CHECK_NON_EMPTY)
        }

        builder.collapsibleGroup("Dependencies") {
            row("Fabric loader") { textField().bindText(loaderVersionProperty) }
            row("Mod dependencies") {
                button("Configure") {
                    val dialog = DependencyListDialog(
                        dialogTitle = "Mod Dependencies",
                        mcVersions = selectedVersions,
                        initial = modDependencies,
                    )
                    if (dialog.showAndGet()) {
                        modDependencies = dialog.getResult()
                    }
                }

                label("").also { label ->
                    modDependenciesProperty.afterChange { list ->
                        label.component.text = if (list.isEmpty()) "" else "${list.size} configured"
                    }
                }
            }
            row("Dev dependencies") {
                button("Configure") {
                    val dialog = DependencyListDialog(
                        dialogTitle = "Dev Dependencies",
                        mcVersions = selectedVersions,
                        initial = devDependencies,
                    )
                    if (dialog.showAndGet()) {
                        devDependencies = dialog.getResult()
                    }
                }
                label("").also { label ->
                    devDependenciesProperty.afterChange { list ->
                        label.component.text = if (list.isEmpty()) "" else "${list.size} configured"
                    }
                }
            }
        }

        builder.collapsibleGroup("Optional Settings") {
            row("Description:") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(descriptionProperty)
            }

            row("Authors:") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(authorsProperty)
            }

            row("Website:") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(websiteProperty)
            }
        }

        builder.collapsibleGroup("Build System Properties") {
            row("Group ID:") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(groupIdProperty)
            }

            row("Artifact ID:") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(artifactIdProperty)
            }

            row("Version:") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(versionProperty)
            }
        }

//        builder.group("Features") {
//            row("Access Widener") {
//                comboBox(AWType.entries.toList())
//                    .bindItem(awTypeProperty)
//                    .applyToComponent { renderer = SimpleListCellRenderer.create("") { it.label ?: "" } }
//            }
//
//            row {
//                checkBox("Mixins").bindSelected(useMixinsProperty)
//            }
//            row {
//                checkBox("Versioned resource packs")
//                    .bindSelected(generateVersionedResourcesProperty)
//                    .comment("Separate resources/ dirs for each version")
//            }
//            row {
//                checkBox("Modrinth / CurseForge publishing")
//                    .bindSelected(generatePublishingProperty)
//                    .comment("Uses modmuss50's mod-publish-plugin for files and minotaur for the description")
//            }
//        }

        loadVersions()
    }

    private fun loadVersions() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val versions = MinecraftVersions.downloadData()
            SwingUtilities.invokeLater {
                versionsLoadingProperty.set(false)
                if (versions.isEmpty()) {
                    noVersionsProperty.set(true)
                    return@invokeLater
                }
                versionPicker.setVersions(versions)
            }
        }
    }

    private fun suggestMainClassName(): String {
        val step = findStep<GrindstoneStep>()

        if (step.artifactId.contains('.')) return step.mainClassProperty.get()

        return step.groupId.toPackageName()
    }

    private fun suggestArtifactId(): String {
        val name = baseData.name
            .lowercase()
            .trim()

        return name
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    override fun setupProject(project: Project) {
        val config = GrindstoneConfig(
            selectedVersions = selectedVersions,
            vcsVersion = vcsVersion,
            fabricLoaderVersion = loaderVersionProperty.get(),
            modDependencies = modDependencies,
            devDependencies = devDependencies,
            fabricApiVersion = fabricApiVersionProperty.get(),
            accessWidenerType = awTypeProperty.get(),
            useMixins = useMixinsProperty.get(),
            generateVersionedResources = generateVersionedResourcesProperty.get(),
            generatePublishing = generatePublishingProperty.get(),
        )
        //  StonecutterProjectGenerator(project, context, config).generate()
    }

    private fun kotlinx.coroutines.CoroutineScope.cancel() {
        this.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}