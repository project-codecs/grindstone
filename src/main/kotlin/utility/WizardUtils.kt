package com.codex.idea.grindstone.utility

import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.util.Key

object WizardUtils {
    inline fun <reified T : NewProjectWizardStep> NewProjectWizardStep.findStep(): T {
        return findStep(T::class.java)
    }

    fun <T : NewProjectWizardStep> NewProjectWizardStep.findStep(clazz: Class<out T>): T {
        return data.getUserData(getOrCreateClassKey(clazz)) ?: throw IllegalStateException("Could not find required step ${clazz.name}")
    }

    private val stepClassToKey = mutableMapOf<Class<*>, Key<*>>()

    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun <T : NewProjectWizardStep> getOrCreateClassKey(clazz: Class<out T>) = stepClassToKey.computeIfAbsent(clazz) { Key.create<T>(it.name) } as Key<T>
}