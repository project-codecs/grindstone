package com.codex.idea.grindstone.wizard;

import com.codex.idea.grindstone.assets.Icons
import com.codex.idea.grindstone.wizard.step.GrindstoneStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.RootNewProjectWizardStep

class GrindstoneWizard : GeneratorNewProjectWizard {
    override val id = "stonecutter"
    override val name = "Stonecutter"
    override val icon = Icons.STONECUTTER

    override fun createStep(context: WizardContext) = RootNewProjectWizardStep(context)
        .nextStep(::NewProjectWizardBaseStep)
        .nextStep(::GitNewProjectWizardStep)
        .nextStep(::GrindstoneStep)

    override val groupName = "Minecraft"
}