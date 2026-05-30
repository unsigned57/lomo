package com.lomo.baseline

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

internal const val STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY = "lomo.staticBaselineProfile.maxTotalEntries"

class StaticBaselineProfilePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val configureAction =
            object : Action<Project> {
                override fun execute(targetProject: Project) {
                    configureAndroidProject(targetProject)
                }
            }
        project.pluginManager.withPlugin("com.android.application") {
            configureAction.execute(project)
        }
        project.pluginManager.withPlugin("com.android.library") {
            configureAction.execute(project)
        }
    }

    private fun configureAndroidProject(project: Project) {
        val components =
            project.extensions.findByName("androidComponents")
                ?: error("StaticBaselineProfilePlugin requires an Android plugin.")
        val selector = checkNotNull(components.invokeNoArg("selector")) { "androidComponents.selector() returned null" }
        val releaseSelector = checkNotNull(selector.invokeMethod("withName", "release")) { "selector.withName(\"release\") returned null" }

        components.invokeMethod(
            "onVariants",
            releaseSelector,
            object : Action<Any> {
                override fun execute(variant: Any) {
                    registerVariantTask(project, variant)
                }
            },
        )
    }

    private fun registerVariantTask(
        project: Project,
        variant: Any,
    ) {
        val variantName = checkNotNull(variant.invokeNoArg("getName")) { "variant.name returned null" } as String
        val taskProvider = registerStaticBaselineProfileTask(project, variantName)

        val artifacts = checkNotNull(variant.invokeNoArg("getArtifacts")) { "variant.artifacts returned null" }
        val scopeAll = enumConstant(artifacts.javaClass.classLoader, "com.android.build.api.variant.ScopedArtifacts\$Scope", "ALL")
        val scopedArtifacts = checkNotNull(artifacts.invokeMethod("forScope", scopeAll)) { "artifacts.forScope(ALL) returned null" }
        val operation = checkNotNull(scopedArtifacts.invokeMethod("use", taskProvider)) { "scopedArtifacts.use(taskProvider) returned null" }
        val classesArtifact = staticField(operation.javaClass.classLoader, "com.android.build.api.artifact.ScopedArtifact", "CLASSES")
        operation.invokeMethod(
            "toGet",
            classesArtifact,
            StaticBaselineProfileTask::allJars,
            StaticBaselineProfileTask::allDirs,
        )
    }

    private fun enumConstant(
        classLoader: ClassLoader,
        className: String,
        constantName: String,
    ): Any {
        val enumClass = Class.forName(className, true, classLoader)
        return enumClass.enumConstants.first { (it as Enum<*>).name == constantName }
    }

    private fun staticField(
        classLoader: ClassLoader,
        className: String,
        fieldName: String,
    ): Any =
        try {
            Class.forName(className, true, classLoader)
                .getField(fieldName)
                .get(null)
        } catch (_: NoSuchFieldException) {
            Class.forName("$className\$$fieldName", true, classLoader)
                .getField("INSTANCE")
                .get(null)
        }

    private fun Any.invokeNoArg(name: String): Any? = invokeMethod(name)

    private fun Any.invokeMethod(
        name: String,
        vararg arguments: Any,
    ): Any? {
        val methods = javaClass.methods.filter { it.name == name && it.parameterCount == arguments.size }
        val method =
            methods.firstOrNull { candidate ->
                candidate.parameterTypes.zip(arguments).all { (parameterType, argument) ->
                    wrap(parameterType).isInstance(argument)
                }
            } ?: error("Failed to find method $name on ${javaClass.name} with ${arguments.size} arguments")
        return method.invoke(this, *arguments)
    }

    private fun wrap(type: Class<*>): Class<*> =
        when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            else -> type
        }
}

internal fun registerStaticBaselineProfileTask(
    project: Project,
    variantName: String,
): TaskProvider<StaticBaselineProfileTask> {
    val taskName = "generate${variantName.replaceFirstChar { it.uppercase() }}StaticBaselineProfile"
    val configuredMaxTotalEntries = project.staticBaselineProfileMaxTotalEntries()
    return project.tasks.register(taskName, StaticBaselineProfileTask::class.java) {
        group = "baseline profile"
        description = "Generate static baseline profile for the $variantName variant."
        projectDirPath.set(project.layout.projectDirectory.asFile.absolutePath)
        rulesFile.set(project.layout.projectDirectory.file("baseline-rules.txt"))
        outputProfile.set(
            project.layout.projectDirectory.dir("src/main/baselineProfiles").file("generated.txt"),
        )
        reportFile.set(
            project.layout.buildDirectory.file("reports/baseline-profile/${variantName}/static-baseline-profile-report.txt"),
        )
        configuredMaxTotalEntries?.let(maxTotalEntries::set)
    }
}

private fun Project.staticBaselineProfileMaxTotalEntries(): Int? {
    val rawValue = findProperty(STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY) ?: return null
    val valueText = rawValue.toString()
    val value =
        valueText.toIntOrNull()
            ?: throw IllegalArgumentException(
                "$STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY must be a non-negative integer: $valueText",
            )
    require(value >= 0) {
        "$STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY must be a non-negative integer: $valueText"
    }
    return value
}
