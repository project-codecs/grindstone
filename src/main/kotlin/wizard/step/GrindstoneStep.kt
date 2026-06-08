package com.codex.idea.grindstone.wizard.step

import com.codex.idea.grindstone.generator.GrindstoneProjectGenerator
import com.codex.idea.grindstone.generator.model.DependencyEntry
import com.codex.idea.grindstone.utility.toPackageName
import com.codex.idea.grindstone.utility.versions.LoaderVersions
import com.codex.idea.grindstone.utility.versions.MavenVersions
import com.codex.idea.grindstone.utility.versions.McVersion
import com.codex.idea.grindstone.utility.versions.MinecraftVersions
import com.codex.idea.grindstone.utility.versions.SemVer
import com.codex.idea.grindstone.wizard.component.DependencyListDialog
import com.codex.idea.grindstone.wizard.component.MappingsConfig
import com.codex.idea.grindstone.wizard.component.MappingsDialog
import com.codex.idea.grindstone.wizard.component.MappingsType
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
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBSlider
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.textValidation
import com.intellij.util.ui.AsyncProcessIcon
import com.sun.management.OperatingSystemMXBean
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.util.Hashtable
import javax.swing.DefaultComboBoxModel
import javax.swing.JLabel
import javax.swing.SwingUtilities

enum class AWType(val label: String) {
    SINGLE("One shared file in src/main/resources"),
    SPREAD("Separate access widener files for all versions"),
    NONE("None");
}

@Serializable
data class McGameVersion(
    val version: String,
    val stable: Boolean,
)

data class FabricLoaderVersion(
    val separator: String,
    val build: Int,
    val maven: String,
    val version: String,
    val stable: Boolean
)

@Serializable
data class GrindstoneConfig(
    val selectedVersions: List<String>,
    val vcsVersion: String,

    val modId: String,
    val modName: String,
    val mainClass: String,

    val groupId: String,
    val artifactId: String,
    val version: String,
    val maxMemoryUsage: Int,

    val loomVersion: String,
    val fabricLoaderVersion: String,
    val fabricApiVersion: String,

    val mappingsType: String,
    val yarnVersions: Map<String, String>,

    val modDependencies: List<DependencyEntry>,
    val devDependencies: List<DependencyEntry>,

    val accessWidenerType: AWType,

    val useMixins: Boolean,
    val useDatagen: Boolean,
    val useSeparatePackges: Boolean,

    val generateVersionedResources: Boolean,
    val generatePublishing: Boolean,
    val generateTests: Boolean,
    val useArtifactCollection: Boolean,
    val useGithubBuildNumber: Boolean
)

class GrindstoneStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    private val baseData = data.getUserData(NewProjectWizardBaseData.KEY) ?: error("Step created without base data")

    private val versionsLoadingProperty = propertyGraph.property(true)
    private val noVersionsProperty = propertyGraph.property(false)

    val selectedVersionsProperty: GraphProperty<List<String>> = propertyGraph.property(emptyList())
    var selectedVersions by selectedVersionsProperty

    val vcsVersionProperty: GraphProperty<String> = propertyGraph.property("")
    var vcsVersion by vcsVersionProperty

    private val loomVersionModel = DefaultComboBoxModel<String>()
    private val loaderVersionModel = DefaultComboBoxModel<String>()

    val loomVersionProperty: GraphProperty<String> = propertyGraph.property("")
    val loaderVersionProperty: GraphProperty<String> = propertyGraph.property("")

    val fabricApiVersionProperty: GraphProperty<String> = propertyGraph.property("")

    val awTypeProperty: GraphProperty<AWType> = propertyGraph.property(AWType.SPREAD)

    val useMixinsProperty = propertyGraph.property(false)
    val useDatagenProperty = propertyGraph.property(false)
    val generateVersionedResourcesProperty = propertyGraph.property(false)
    val generateTestsProperty = propertyGraph.property(false)
    val useSeparatePackagesProperty = propertyGraph.property(false)
    val generatePublishingProperty = propertyGraph.property(false)
    val artifactCollectionProperty = propertyGraph.property(true)
    val githubBuildNumberProperty = propertyGraph.property(false)

    val modIdProperty = propertyGraph.property("")
    val modNameProperty = propertyGraph.property("")
    val mainClassProperty = propertyGraph.lazyProperty(::suggestMainClassName)

    val groupIdProperty = propertyGraph.property("org.example").bindStorage("${javaClass.name}.groupId")
    val artifactIdProperty = propertyGraph.lazyProperty(modNameProperty::get)
    val versionProperty = propertyGraph.property("1.0-SNAPSHOT").bindStorage("${javaClass.name}.version")

    val modDependenciesProperty: GraphProperty<List<DependencyEntry>> = propertyGraph.property(emptyList())
    var modDependencies by modDependenciesProperty

    val devDependenciesProperty: GraphProperty<List<DependencyEntry>> = propertyGraph.property(emptyList())
    var devDependencies by devDependenciesProperty

    val mappingsConfigProperty: GraphProperty<MappingsConfig> = propertyGraph.property(MappingsConfig(MappingsType.OFFICIAL))
    var mappingsConfig by mappingsConfigProperty

    val maxMemoryProperty = propertyGraph.property(2048)

    private lateinit var versionPicker: McVersionPickerPanel
    private val controllerComboModel = DefaultComboBoxModel<String>()

    init {
        selectedVersionsProperty.afterChange { versions ->
            val sorted = versions.sortedWith(compareByDescending { McVersion.parse(it) })
            controllerComboModel.removeAllElements()
            sorted.forEach { controllerComboModel.addElement(it) }
            if (vcsVersion !in sorted) vcsVersion = sorted.firstOrNull() ?: ""
        }

        modIdProperty.dependsOn(modNameProperty) {
            modNameProperty.get()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
        }

        artifactIdProperty.dependsOn(baseData.nameProperty, ::suggestArtifactId)
        mainClassProperty.dependsOn(modIdProperty, ::suggestMainClassName)
        mainClassProperty.dependsOn(groupIdProperty, ::suggestMainClassName)
        mainClassProperty.dependsOn(useSeparatePackagesProperty, ::suggestMainClassName)

        modNameProperty.dependsOn(baseData.nameProperty, baseData.nameProperty::get)
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

        builder.collapsibleGroup("Minecraft Versions") {
            row("Versions: ") {
                versionPicker = McVersionPickerPanel { selectedVersions = it; mappingsConfigProperty.set(mappingsConfigProperty.get()) }
                cell(versionPicker).align(AlignX.FILL)
            }

            row("VCS version: ") {
                comboBox(controllerComboModel)
                    .bindItem(vcsVersionProperty)
            }
        }.visibleIf(versionsLoadingProperty.not())

        builder.collapsibleGroup("Project") {
            row("Mod name: ") {
                textField()
                    .bindText(modNameProperty)
                    .columns(COLUMNS_LARGE)
            }

            row("Mod ID: ") {
                textField()
                    .bindText(modIdProperty)
                    .columns(COLUMNS_LARGE)
                    .textValidation(CHECK_NON_EMPTY)
            }

            row("Main class: ") {
                textField()
                    .bindText(mainClassProperty)
                    .columns(COLUMNS_LARGE)
            }
        }

        builder.collapsibleGroup("Toolchain and Dependencies") {
            row("Loom version: ") { comboBox(loomVersionModel).bindItem(loomVersionProperty).enabledIf(versionsLoadingProperty.not()) }
            row("Fabric loader: ") { comboBox(loaderVersionModel).bindItem(loaderVersionProperty).enabledIf(versionsLoadingProperty.not()) }

            row("Mod dependencies") {
                button("Configure") {
                    val dialog = DependencyListDialog(
                        dialogTitle = "Mod Dependencies",
                        mcVersions = selectedVersions,
                        initial = modDependencies,
                    )
                    if (dialog.showAndGet()) modDependencies = dialog.getResult()
                }
            }

            row("Dev dependencies") {
                button("Configure") {
                    val dialog = DependencyListDialog(
                        dialogTitle = "Dev Dependencies",
                        mcVersions = selectedVersions,
                        initial = devDependencies,
                    )
                    if (dialog.showAndGet()) devDependencies = dialog.getResult()
                }
            }

            row {
                val summaryLabel = label(mappingsLabel()).component
                mappingsConfigProperty.afterChange { summaryLabel.text = mappingsLabel() }

                button("Configure") {
                    val dialog = MappingsDialog(
                        mcVersions = selectedVersions,
                        initial = mappingsConfig
                    )

                    if (dialog.showAndGet()) {
                        mappingsConfig = dialog.getResult()
                        loadVersions()
                    }
                    summaryLabel.text = mappingsLabel()
                }
            }
        }

        builder.collapsibleGroup("Features") {
            row("Access Widener") {
                comboBox(AWType.entries.toList())
                    .bindItem(awTypeProperty)
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create("") { it.label }
                    }
            }

            row {
                checkBox("Use mixins").bindSelected(useMixinsProperty)
                checkBox("Use datagen").bindSelected(useDatagenProperty)
                checkBox("Use publishing (Modrinth)").bindSelected(generatePublishingProperty)
            }

            row {
                checkBox("Separate resources")
                    .bindSelected(generateVersionedResourcesProperty)
                    .comment("Generate separate src/main/resources folders for all versions.")
            }

            row {
                checkBox("Generate test config")
                    .bindSelected(generateTestsProperty)
                    .comment("Generate src/tests and add junit configuration to build files.")
            }

            row {
                checkBox("Use separate packages")
                    .bindSelected(useSeparatePackagesProperty)
                    .comment("Put all initialization entrypoints into different packages like common and common.data.")
            }

            row {
                checkBox("Use artifact collection")
                    .bindSelected(artifactCollectionProperty)
                    .comment("Add a gradle task 'buildAndCollect' which builds all versions and collects the jar files in /artifacts.")
            }

            row {
                checkBox("Use GitHub build num in version")
                    .bindSelected(githubBuildNumberProperty)
                    .comment("Add -\$buildNum to the archive java file.")
            }
        }

        builder.collapsibleGroup("Build System") {
            row("Group ID: ") {
                textField().bindText(groupIdProperty)
            }

            row("Artifact ID: ") {
                textField().bindText(artifactIdProperty)
            }

            row("Version: ") {
                textField().bindText(versionProperty)
            }

            row("Maximum RAM usage: ") {
                val min = 1024
                val max = getMaxRecommendedRamMb()

                val slider = JBSlider(JBSlider.HORIZONTAL, min, max, 2048).apply {
                    paintTicks = true
                    paintLabels = true
                    snapToTicks = true

                    majorTickSpacing = 1024
                    minorTickSpacing = 256

                    // Show tick labels as GB
                    val labels = Hashtable<Int, JLabel>()
                    for (value in min..max step majorTickSpacing) {
                        labels[value] = JLabel("${value / 1024}G")
                    }
                    labelTable = labels
                }

                val valueLabel = JBLabel("${slider.value} MB").apply {
                    val size = JBLabel("XXXXXXXX").preferredSize
                    minimumSize = size
                    preferredSize = size
                    maximumSize = size
                }

                slider.addChangeListener {
                    val valueMb = slider.value

                    valueLabel.text = "$valueMb MB"
                    maxMemoryProperty.set(valueMb)
                }

                cell(valueLabel)
                cell(slider)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
        }

        loadVersions()
    }

    private fun mappingsLabel(): String {
        val cfg = mappingsConfig
        return "Mappings: " + when (cfg.type) {
            MappingsType.OFFICIAL -> "Official (Mojmaps)"
            MappingsType.YARN -> {
                val filled = cfg.yarnVersions.values.count { it.isNotBlank() }
                val total = selectedVersions.size
                if (filled == total && total > 0) "Yarn ($filled/${total} configured)"
                else if (total == 0) "Yarn (no MC versions selected)"
                else "Yarn ($filled/${total} configured, open to complete)"
            }
        }
    }

    fun getMaxRecommendedRamMb(): Int {
        val osBean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean

        val totalBytes = osBean.totalMemorySize

        val oneGbBytes = 1024L * 1024L * 1024L
        val usable = (totalBytes - oneGbBytes).coerceAtLeast(512L * 1024 * 1024)

        return 12288.coerceAtMost((usable / (1024 * 1024)).toInt())
    }

    private fun loadVersions() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val mcVersions = MinecraftVersions.downloadData(mappingsConfig.type == MappingsType.YARN)
            val loaderVersions = LoaderVersions.downloadData()
            val loomVersions: List<String> = MavenVersions.fetchVersions("https://maven.fabricmc.net/", "net.fabricmc", "fabric-loom").sortedByDescending { SemVer.parse(it) }

            SwingUtilities.invokeLater {
                versionsLoadingProperty.set(false)

                loaderVersionModel.removeAllElements()
                loaderVersions.forEach { loaderVersionModel.addElement(it.version) }

                if (loaderVersionProperty.get().isBlank()) {
                    loaderVersionProperty.set(loaderVersions.firstOrNull()?.version ?: "")
                }

                loomVersionModel.removeAllElements()
                loomVersions.forEach { loomVersionModel.addElement(it) }

                if (loomVersionProperty.get().isBlank()) {
                    loomVersionProperty.set(loomVersions.firstOrNull() ?: "")
                }

                if (mcVersions.isEmpty()) {
                    noVersionsProperty.set(true)
                    return@invokeLater
                }

                versionPicker.setVersions(mcVersions)
            }
        }
    }

    private fun suggestMainClassName(): String {
        if (artifactIdProperty.get().contains('.')) return mainClassProperty.get()
        return "${groupIdProperty.get().toPackageName()}.${modIdProperty.get()}${if (useSeparatePackagesProperty.get()) ".common." else "."}${modNameProperty.get()}"
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
        val cfg = mappingsConfig
        val config = GrindstoneConfig(
            selectedVersions = selectedVersions,
            vcsVersion = vcsVersion,

            modId = modIdProperty.get(),
            modName = modNameProperty.get(),
            mainClass = modNameProperty.get(),

            groupId = groupIdProperty.get(),
            artifactId = artifactIdProperty.get(),
            version = versionProperty.get(),
            maxMemoryUsage = maxMemoryProperty.get(),

            loomVersion = loomVersionProperty.get(),
            fabricLoaderVersion = loaderVersionProperty.get(),
            fabricApiVersion = fabricApiVersionProperty.get(),

            mappingsType = cfg.type.name.lowercase(),
            yarnVersions = cfg.yarnVersions,

            modDependencies = modDependencies,
            devDependencies = devDependencies,

            accessWidenerType = awTypeProperty.get(),
            useMixins = useMixinsProperty.get(),
            useDatagen = useDatagenProperty.get(),
            useSeparatePackges = useSeparatePackagesProperty.get(),

            generateVersionedResources = generateVersionedResourcesProperty.get(),
            generatePublishing = generatePublishingProperty.get(),
            generateTests = generateTestsProperty.get(),
            useArtifactCollection = artifactCollectionProperty.get(),
            useGithubBuildNumber = githubBuildNumberProperty.get()
        )

        GrindstoneProjectGenerator(project, context, config).generate()
    }
}