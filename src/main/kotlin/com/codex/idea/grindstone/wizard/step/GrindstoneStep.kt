package com.codex.idea.grindstone.wizard.step

import com.codex.idea.grindstone.generator.GrindstoneProjectGenerator
import com.codex.idea.grindstone.generator.model.DependencyEntry
import com.codex.idea.grindstone.utility.toPackageName
import com.codex.idea.grindstone.utility.translate
import com.codex.idea.grindstone.utility.versions.LoaderVersions
import com.codex.idea.grindstone.utility.versions.MavenVersions
import com.codex.idea.grindstone.utility.versions.McVersion
import com.codex.idea.grindstone.utility.versions.MinecraftVersions
import com.codex.idea.grindstone.utility.versions.SemVer
import com.codex.idea.grindstone.wizard.component.DependencyListDialog
import com.codex.idea.grindstone.wizard.component.MappingsConfig
import com.codex.idea.grindstone.wizard.component.MappingsDialog
import com.codex.idea.grindstone.wizard.component.MappingsType
import com.codex.idea.grindstone.wizard.component.McVersionPickerPanel
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.util.bindStorage
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
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
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities

enum class AWType(val label: String, val perVersion: Boolean = false, val classTweaker: Boolean = false) {
    SINGLE(translate("awType.single")),
    SPREAD(translate("awType.spread"), perVersion = true),
    CLASS_TWEAKER_SINGLE(translate("awType.classTweaker.single"), classTweaker = true),
    CLASS_TWEAKER_SPREAD(translate("awType.classTweaker.spread"), perVersion = true, classTweaker = true),
    NONE(translate("none"));

    val shared: Boolean get() = this != NONE && !perVersion
    val extension: String get() = if (classTweaker) "classtweaker" else "accesswidener"
    val header: String get() = if (classTweaker) "classTweaker v2 named\n" else "accessWidener v2 named\n"
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
    val useGithubBuildNumber: Boolean,
    val license: ProjectLicense = ProjectLicense.ALL_RIGHTS_RESERVED,
    val licenseFileName: String = "LICENSE",
    val copyrightHolder: String = "",
    val generateChangelog: Boolean = true,
    val addTreeComments: Boolean,
)

class GrindstoneStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    private val baseData = data.getUserData(NewProjectWizardBaseData.KEY) ?: error(translate("error.noData"))

    private val licenseProperty = propertyGraph.property(ProjectLicense.ALL_RIGHTS_RESERVED)
    private val licenseFileProperty = propertyGraph.property("LICENSE")
    private val copyrightHolderProperty = propertyGraph.property("")
    private val changelogProperty = propertyGraph.property(true)
    private var versionsLoadRequest = 0

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

    val addTreeCommentsProperty: GraphProperty<Boolean> = propertyGraph.property(false);
    var addTreeComments by addTreeCommentsProperty

    val maxMemoryProperty = propertyGraph.property(2048)

    private lateinit var versionPicker: McVersionPickerPanel
    private val controllerComboModel = DefaultComboBoxModel<String>()

    init {
        selectedVersionsProperty.afterChange { versions ->
            val sorted = versions.sortedWith(compareByDescending { McVersion.parse(it) })
            val controller = vcsVersion
            controllerComboModel.removeAllElements()
            sorted.forEach { controllerComboModel.addElement(it) }
            vcsVersion = controller.takeIf { it in sorted } ?: sorted.firstOrNull().orEmpty()
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
        builder.presetRow()

        builder.row {
            cell(AsyncProcessIcon("StonecutterStep versions load"))
            label(translate("loading.mcVersions"))
        }.visibleIf(versionsLoadingProperty)

        builder.row {
            label(translate("error.couldNotLoadVersions"))
                .apply { component.foreground = JBColor.RED }
        }.visibleIf(noVersionsProperty)

        builder.row(translate("property.mcVersions.versions", after = 1)) {
            val pickerButton = JButton("Choose versions...")

            versionPicker = McVersionPickerPanel { versions ->
                selectedVersions = versions
                mappingsConfigProperty.set(mappingsConfigProperty.get())

                pickerButton.text = if (versions.isEmpty()) {
                    "Choose versions..."
                } else {
                    "${versions.size} selected"
                }
            }

            pickerButton.addActionListener {
                versionPicker.spawnPopup(pickerButton)
            }

            cell(pickerButton).align(AlignX.FILL)
        }

        builder.collapsibleGroup(translate("property.project")) {
            row(translate("property.project.modName", after = 1)) {
                textField()
                    .bindText(modNameProperty)
                    .columns(COLUMNS_LARGE)
            }

            row(translate("property.project.modId", after = 1)) {
                textField()
                    .bindText(modIdProperty)
                    .columns(COLUMNS_LARGE)
                    .textValidation(CHECK_NON_EMPTY)
            }

            row(translate("property.project.mainClass", after = 1)) {
                textField()
                    .bindText(mainClassProperty)
                    .columns(COLUMNS_LARGE)
            }
        }

        builder.collapsibleGroup(translate("property.toolchain")) {
            row(translate("property.toolchain.loomVersion", after = 1)) { comboBox(loomVersionModel).bindItem(loomVersionProperty).enabledIf(versionsLoadingProperty.not()) }
            row(translate("property.toolchain.loaderVersion", after = 1)) { comboBox(loaderVersionModel).bindItem(loaderVersionProperty).enabledIf(versionsLoadingProperty.not()) }

            row(translate("dependency.mod")) {
                button(translate("configure")) {
                    val dialog = DependencyListDialog(
                        dialogTitle = translate("dependency.mod"),
                        mcVersions = selectedVersions,
                        initial = modDependencies,
                    )
                    if (dialog.showAndGet()) modDependencies = dialog.getResult()
                }
            }

            row(translate("dependency.dev")) {
                button(translate("configure")) {
                    val dialog = DependencyListDialog(
                        dialogTitle = translate("dependency.dev"),
                        mcVersions = selectedVersions,
                        initial = devDependencies,
                    )
                    if (dialog.showAndGet()) devDependencies = dialog.getResult()
                }
            }

            row {
                val summaryLabel = label(mappingsLabel()).component
                mappingsConfigProperty.afterChange { summaryLabel.text = mappingsLabel() }

                button(translate("configure")) {
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

        builder.collapsibleGroup(translate("property.features")) {
            row(translate("property.features.license")) {
                comboBox(ProjectLicense.entries.toList()).bindItem(licenseProperty).applyToComponent {
                    renderer = SimpleListCellRenderer.create<ProjectLicense> { label, license, _ ->
                        label.text = license?.id.orEmpty()
                    }
                }
            }

            row(translate("property.features.licenseFile")) {
                textField().bindText(licenseFileProperty)
                    .comment(translate("property.features.licenseFile.comment"))
                    .validationOnApply {
                        configurationError(currentConfig())?.let { message -> ValidationInfo(message) }
                    }
            }

            row(translate("property.features.copyrightHolder")) {
                textField().bindText(copyrightHolderProperty)
                    .comment(translate("property.features.copyrightHolder.comment"))
            }

            row {
                checkBox(translate("property.features.changelog")).bindSelected(changelogProperty)
            }

            row(translate("property.features.aw")) {
                comboBox(AWType.entries.toList())
                    .bindItem(awTypeProperty)
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create<AWType> { label, type, _ ->
                            label.text = type?.label.orEmpty()
                        }
                    }
            }

            row {
                checkBox(translate("property.features.mixins")).bindSelected(useMixinsProperty)
                checkBox(translate("property.features.datagen")).bindSelected(useDatagenProperty)
                checkBox(translate("property.features.publishing")).bindSelected(generatePublishingProperty)
            }

            row {
                checkBox(translate("property.features.addTreeComments")).bindSelected(addTreeCommentsProperty).comment(translate("property.features.addTreeComments.comment"))
            }

            row {
                checkBox(translate("property.features.separateResources"))
                    .bindSelected(generateVersionedResourcesProperty)
                    .comment(translate("property.features.separateResources.comment"))
            }

            row {
                checkBox(translate("property.features.generateTests"))
                    .bindSelected(generateTestsProperty)
                    .comment(translate("property.features.generateTests.comment"))
            }

            row {
                checkBox(translate("property.features.separatePackages"))
                    .bindSelected(useSeparatePackagesProperty)
                    .comment(translate("property.features.separatePackages.comment"))
            }

            row {
                checkBox(translate("property.features.artifactCollection"))
                    .bindSelected(artifactCollectionProperty)
                    .comment(translate("property.features.artifactCollection.comment"))
            }

            row {
                checkBox(translate("property.features.githubBuildNum"))
                    .bindSelected(githubBuildNumberProperty)
                    .comment(translate("property.features.githubBuildNum.comment"))
            }
        }

        builder.collapsibleGroup(translate("property.buildSystem")) {
            row(translate("property.buildSystem.groupId")) {
                textField().bindText(groupIdProperty)
            }

            row(translate("property.buildSystem.artifactId")) {
                textField().bindText(artifactIdProperty)
            }

            row(translate("property.buildSystem.version")) {
                textField().bindText(versionProperty)
            }

            row(translate("property.buildSystem.maxRam")) {
                val min = 1024
                val max = getMaxRecommendedRamMb().coerceAtLeast(min)

                val slider = JBSlider(JBSlider.HORIZONTAL, min, max, maxMemoryProperty.get().coerceIn(min, max)).apply {
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

                maxMemoryProperty.afterChange { slider.value = it.coerceIn(slider.minimum, slider.maximum) }
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
        return when (cfg.type) {
            MappingsType.OFFICIAL -> translate("mappings", 0, 0, translate("mappings.official"))
            MappingsType.YARN -> {
                val filled = cfg.yarnVersions.values.count { it.isNotBlank() }
                val total = selectedVersions.size
                if (filled == total && total > 0) translate("mappings", 0, 0, translate("mappings.yarn", 0, 0, filled, total))
                else if (total == 0) translate("mappings", 0, 0, translate("mappings.yarn.empty"))
                else translate("mappings", 0, 0, translate("mappings.yarn.incomplete", 0, 0, filled, total))
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
        val request = ++versionsLoadRequest
        val yarn = mappingsConfig.type == MappingsType.YARN
        versionsLoadingProperty.set(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val mcVersions = MinecraftVersions.downloadData(yarn)
            val loaderVersions = LoaderVersions.downloadData()
            val loomVersions: List<String> = MavenVersions.fetchVersions("https://maven.fabricmc.net/", "net.fabricmc", "fabric-loom").sortedByDescending { SemVer.parse(it) }

            SwingUtilities.invokeLater {
                if (request != versionsLoadRequest) return@invokeLater
                versionsLoadingProperty.set(false)
                noVersionsProperty.set(mcVersions.isEmpty())

                val requestedLoader = loaderVersionProperty.get()
                val requestedLoom = loomVersionProperty.get()
                loaderVersionModel.removeAllElements()
                loaderVersions.forEach { loaderVersionModel.addElement(it.version) }

                if (requestedLoader.isNotBlank() && loaderVersionModel.getIndexOf(requestedLoader) == -1)
                    loaderVersionModel.addElement(requestedLoader)
                loaderVersionProperty.set(requestedLoader.ifBlank { loaderVersions.firstOrNull()?.version.orEmpty() })

                loomVersionModel.removeAllElements()
                loomVersions.forEach { loomVersionModel.addElement(it) }

                if (requestedLoom.isNotBlank() && loomVersionModel.getIndexOf(requestedLoom) == -1)
                    loomVersionModel.addElement(requestedLoom)
                loomVersionProperty.set(requestedLoom.ifBlank { loomVersions.firstOrNull().orEmpty() })

                if (mcVersions.isNotEmpty()) versionPicker.setVersions(mcVersions)
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

    private fun Panel.presetRow() {
        val directory = File(PathManager.getConfigPath(), "grindstone/presets")
        val placeholder = translate("preset.select")
        val savedPresets = ComboBox<String>()

        fun refreshPresets() {
            val names = directory.listFiles().orEmpty()
                .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                .map { it.name }
                .sortedBy { it.lowercase() }

            savedPresets.model = DefaultComboBoxModel(
                (listOf(placeholder) + names).toTypedArray()
            )
        }

        fun openPreset(file: File) {
            runCatching {
                applyPreset(PresetFiles.open(file.toPath()))
            }.onFailure {
                Messages.showErrorDialog(it.localizedMessage ?: translate("preset.error.open"), translate("preset.open"))
            }
        }

        fun savePreset(file: File) {
            if (file.exists() && Messages.showYesNoDialog(
                    translate("preset.replace", params = arrayOf(file.name)),
                    translate("preset.save"),
                    Messages.getQuestionIcon()
                ) != Messages.YES
            ) return

            runCatching {
                java.nio.file.Files.createDirectories(file.toPath().toAbsolutePath().parent)
                PresetFiles.save(file.toPath(), currentConfig())
            }.onSuccess {
                refreshPresets()
            }.onFailure {
                Messages.showErrorDialog(it.localizedMessage ?: translate("preset.error.save"), translate("preset.save"))
            }
        }

        fun jsonName(name: String): String =
            if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"

        fun chooser(): JFileChooser = JFileChooser(directory.takeIf { it.isDirectory }).apply {
            fileFilter = FileNameExtensionFilter(translate("preset.fileType"), "json")
        }

        refreshPresets()
        savedPresets.addActionListener {
            val index = savedPresets.selectedIndex
            if (index > 0) {
                val name = savedPresets.getItemAt(index)
                savedPresets.selectedIndex = 0
                openPreset(File(directory, name))
            }
        }

        row(translate("preset.group")) {
            button(translate("preset.open")) { event ->
                val dialog = chooser()
                if (dialog.showOpenDialog(event.source as JComponent) == JFileChooser.APPROVE_OPTION) {
                    openPreset(dialog.selectedFile)
                }
            }

            button(translate("preset.save")) { event ->
                val anchor = event.source as JComponent
                JPopupMenu().apply {
                    add(JMenuItem(translate("preset.save")).apply {
                        addActionListener {
                            val name = Messages.showInputDialog(
                                translate("preset.enterName"),
                                translate("preset.save"),
                                Messages.getQuestionIcon()
                            )?.trim() ?: return@addActionListener

                            if (name.isBlank() || name == "." || name == ".." ||
                                name.any { it.isISOControl() || it in "<>:\"/\\|?*" }
                            ) {
                                Messages.showErrorDialog(translate("preset.invalidName"), translate("preset.save"))
                                return@addActionListener
                            }

                            savePreset(File(directory, jsonName(name)))
                        }
                    })

                    add(JMenuItem(translate("preset.saveAs")).apply {
                        addActionListener {
                            val dialog = chooser()
                            if (dialog.showSaveDialog(anchor) == JFileChooser.APPROVE_OPTION) {
                                val selected = dialog.selectedFile
                                savePreset(File(selected.parentFile, jsonName(selected.name)))
                            }
                        }
                    })
                }.show(anchor, 0, anchor.height)
            }

            cell(savedPresets).align(AlignX.FILL)
        }
    }

    private fun currentConfig(): GrindstoneConfig {
        val cfg = mappingsConfig
        return GrindstoneConfig(
            selectedVersions = selectedVersions,
            vcsVersion = vcsVersion,

            modId = modIdProperty.get(),
            modName = modNameProperty.get(),
            mainClass = mainClassProperty.get(),

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
            useGithubBuildNumber = githubBuildNumberProperty.get(),
            license = licenseProperty.get(),
            licenseFileName = licenseFileProperty.get().trim(),
            copyrightHolder = copyrightHolderProperty.get().trim(),
            generateChangelog = changelogProperty.get(),
            addTreeComments = addTreeCommentsProperty.get()
        )
    }

    private fun applyPreset(config: GrindstoneConfig) {
        versionPicker.setSelectedVersions(config.selectedVersions)
        vcsVersion = config.vcsVersion.takeIf { it in config.selectedVersions } ?: vcsVersion

        modNameProperty.set(config.modName)
        modIdProperty.set(config.modId)
        groupIdProperty.set(config.groupId)
        artifactIdProperty.set(config.artifactId)
        versionProperty.set(config.version)
        maxMemoryProperty.set(config.maxMemoryUsage.coerceIn(1024, getMaxRecommendedRamMb().coerceAtLeast(1024)))

        fabricApiVersionProperty.set(config.fabricApiVersion)
        loomVersionProperty.set(config.loomVersion)
        loaderVersionProperty.set(config.fabricLoaderVersion)
        mappingsConfig = MappingsConfig(MappingsType.valueOf(config.mappingsType.uppercase()), config.yarnVersions)
        modDependencies = config.modDependencies
        devDependencies = config.devDependencies

        awTypeProperty.set(config.accessWidenerType)
        useMixinsProperty.set(config.useMixins)
        useDatagenProperty.set(config.useDatagen)
        useSeparatePackagesProperty.set(config.useSeparatePackges)
        mainClassProperty.set(config.mainClass)
        generateVersionedResourcesProperty.set(config.generateVersionedResources)
        generatePublishingProperty.set(config.generatePublishing)
        generateTestsProperty.set(config.generateTests)
        artifactCollectionProperty.set(config.useArtifactCollection)
        githubBuildNumberProperty.set(config.useGithubBuildNumber)
        licenseProperty.set(config.license)
        licenseFileProperty.set(config.licenseFileName)
        copyrightHolderProperty.set(config.copyrightHolder)
        changelogProperty.set(config.generateChangelog)
        addTreeCommentsProperty.set(config.addTreeComments)
        loadVersions()
    }

    private fun configurationError(config: GrindstoneConfig): String? {
        if (!ProjectLicense.validFileName(config.licenseFileName))
            return translate("property.features.licenseFile.invalid")

        if (config.accessWidenerType.classTweaker) {
            val supported = runCatching {
                val loom = SemVer.parse(config.loomVersion)
                val loader = SemVer.parse(config.fabricLoaderVersion)
                compareValues(loom, SemVer.parse("1.12.0")) >= 0 &&
                    compareValues(loader, SemVer.parse("0.18.0")) >= 0
            }.getOrDefault(false)
            if (!supported) return translate("awType.classTweaker.requirements")
        }

        for (dependencies in listOf(config.modDependencies, config.devDependencies)) {
            if (dependencies.distinctBy { it.modId }.size != dependencies.size)
                return translate("dependency.error.duplicateId")
            val missing = dependencies.firstOrNull { dependency ->
                config.selectedVersions.any { dependency.versionFor(it).isBlank() }
            }
            if (missing != null) return translate("dependency.error.incomplete", params = arrayOf(missing.modId))
        }
        return null
    }

    override fun setupProject(project: Project) {
        val config = currentConfig()
        val error = configurationError(config)
        if (error != null) {
            Messages.showErrorDialog(error, translate("error.invalidConfiguration"))
            return
        }
        GrindstoneProjectGenerator(project, context, config).generate()
    }
}
