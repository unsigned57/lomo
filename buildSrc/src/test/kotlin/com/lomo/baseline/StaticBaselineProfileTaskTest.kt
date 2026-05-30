package com.lomo.baseline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.gradle.testfixtures.ProjectBuilder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/*
 * Behavior Contract:
 * - Unit under test: StaticBaselineProfileTask in the build-system layer.
 * - Owning layer: build-system.
 * - Priority tier: P2.
 * - Capability: generate deterministic static baseline profile artifacts with an auditable report and
 *   configurable total-entry budget.
 *
 * Scenarios:
 * - Given jar and directory class inputs, when generation runs, then matched class and method profile lines are
 *   emitted in sorted order.
 * - Given generated entries span multiple package prefixes and rule globs, when generation runs, then the report
 *   records total entries, package-prefix counts, and matched baseline rule/glob counts.
 * - Given a configured maxTotalEntries budget below the generated entry count, when generation runs, then the task
 *   fails and reports the actual total entry count.
 * - Given an empty rules file, when generation runs, then the task fails fast before emitting an invalid profile.
 *
 * Observable outcomes:
 * - Generated profile file contents, generated report file contents, IllegalArgumentException for invalid input,
 *   and IllegalStateException for budget overrun.
 *
 * TDD proof:
 * - RED command: ./gradlew --no-daemon --no-configuration-cache --dependency-verification off --console=plain
 *   :buildSrc:test
 * - Observed RED: compile/test failed before the task report and budget properties existed; the audit-report
 *   scenario could not configure reportFile, and the budget-overrun scenario could not configure maxTotalEntries.
 * - Why RED proves the behavior was missing: the task had no observable report output and no configurable entry
 *   budget, so the generated static baseline profile could grow without an auditable limit.
 *
 * Excludes:
 * - AndroidComponents wiring, release-variant registration, and app module contract coverage.
 */
class StaticBaselineProfileTaskTest : FunSpec({
    test("generate emits matched class and method lines from jars and directories in sorted order") {
        val workspace = prepareWorkspace("sorted-output")
        val jarFile = workspace.resolve("inputs/classes.jar")
        val dirInput = workspace.resolve("inputs/dir-classes")
        val rulesFile = workspace.resolve("baseline-rules.txt")
        val outputFile = workspace.resolve("out/generated.txt")

        writeJar(
            jarFile,
            mapOf(
                "com/example/Foo.class" to
                    classBytes(
                        "com/example/Foo",
                        listOf(
                            MethodSpec("<init>", "()V"),
                            MethodSpec("alpha", "()V"),
                            MethodSpec("beta", "(I)Ljava/lang/String;"),
                        ),
                    ),
                "com/example/Bar.class" to
                    classBytes(
                        "com/example/Bar",
                        listOf(
                            MethodSpec("<init>", "()V"),
                            MethodSpec("ignored", "()V"),
                        ),
                    ),
            ),
        )
        writeClassFile(
            dirInput,
            "com/example/dir/Baz",
            classBytes(
                "com/example/dir/Baz",
                listOf(
                    MethodSpec("<init>", "()V"),
                    MethodSpec("gamma", "()V"),
                ),
            ),
        )
        rulesFile.writeText(
            """
            HSPL com/example/Foo
            SPL com/example/dir/*
            """.trimIndent(),
        )

        val task = createTask(workspace, jarFile, dirInput, rulesFile, outputFile)

        task.generate()

        val emittedLines =
            outputFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }

        val expectedLines =
            listOf(
                "HSPLcom/example/Foo;-><init>()V",
                "HSPLcom/example/Foo;->alpha()V",
                "HSPLcom/example/Foo;->beta(I)Ljava/lang/String;",
                "Lcom/example/Foo;",
                "Lcom/example/dir/Baz;",
                "SPLcom/example/dir/Baz;-><init>()V",
                "SPLcom/example/dir/Baz;->gamma()V",
            ).sorted()

        emittedLines shouldBe expectedLines
        emittedLines.none { it.contains("com/example/Bar") } shouldBe true
    }

    test("generate fails when rules file is empty") {
        val workspace = prepareWorkspace("empty-rules")
        val rulesFile = workspace.resolve("baseline-rules.txt").apply { writeText("\n# comment only\n") }
        val outputFile = workspace.resolve("out/generated.txt")
        val task =
            createTask(
                workspace = workspace,
                jarFile = workspace.resolve("inputs/empty.jar").apply { writeJar(this, emptyMap()) },
                dirInput = workspace.resolve("inputs/dir-classes").apply { mkdirs() },
                rulesFile = rulesFile,
                outputFile = outputFile,
            )

        val error =
            shouldThrow<IllegalArgumentException> {
                task.generate()
            }

        error.message.orEmpty().lowercase() shouldContain "empty"
    }

    test("given matched classes when generation runs then audit report summarizes totals prefixes and rule globs") {
        val workspace = prepareWorkspace("audit-report")
        val jarFile = workspace.resolve("inputs/classes.jar")
        val dirInput = workspace.resolve("inputs/dir-classes")
        val rulesFile = workspace.resolve("baseline-rules.txt")
        val outputFile = workspace.resolve("out/generated.txt")
        val reportFile = workspace.resolve("out/report.txt")

        writeJar(
            jarFile,
            mapOf(
                "com/example/Foo.class" to
                    classBytes(
                        "com/example/Foo",
                        listOf(
                            MethodSpec("<init>", "()V"),
                            MethodSpec("alpha", "()V"),
                        ),
                    ),
                "org/sample/analytics/Tracked.class" to
                    classBytes(
                        "org/sample/analytics/Tracked",
                        listOf(
                            MethodSpec("<init>", "()V"),
                            MethodSpec("record", "(I)V"),
                        ),
                    ),
            ),
        )
        writeClassFile(
            dirInput,
            "com/example/feature/Baz",
            classBytes(
                "com/example/feature/Baz",
                listOf(
                    MethodSpec("<init>", "()V"),
                    MethodSpec("gamma", "()V"),
                ),
            ),
        )
        rulesFile.writeText(
            """
            HSPL com/example/Foo
            SPL com/example/feature/*
            PL org/sample/**
            HSPL com/missing/**
            """.trimIndent(),
        )

        val task = createTask(workspace, jarFile, dirInput, rulesFile, outputFile, reportFile)

        task.generate()

        reportFile.readLines() shouldBe
            listOf(
                "# Generated by static baseline profile task.",
                "totalEntries=9",
                "configuredRuleGlobs=4",
                "matchedRuleGlobs=3",
                "maxTotalEntries=unbounded",
                "budgetStatus=PASS",
                "",
                "[entriesByPackagePrefix]",
                "com/example=3",
                "com/example/feature=3",
                "org/sample=3",
                "",
                "[entriesByBaselineRuleGlob]",
                "HSPL com/example/Foo=3",
                "PL org/sample/**=3",
                "SPL com/example/feature/*=3",
            )
    }

    test("given generated entries exceed maxTotalEntries when generation runs then task fails with actual total") {
        val workspace = prepareWorkspace("entry-budget")
        val jarFile = workspace.resolve("inputs/classes.jar")
        val dirInput = workspace.resolve("inputs/dir-classes").apply { mkdirs() }
        val rulesFile = workspace.resolve("baseline-rules.txt")
        val outputFile = workspace.resolve("out/generated.txt")
        val reportFile = workspace.resolve("out/report.txt")

        writeJar(
            jarFile,
            mapOf(
                "com/example/Foo.class" to
                    classBytes(
                        "com/example/Foo",
                        listOf(
                            MethodSpec("<init>", "()V"),
                            MethodSpec("alpha", "()V"),
                        ),
                    ),
            ),
        )
        rulesFile.writeText("HSPL com/example/Foo")
        val task =
            createTask(
                workspace = workspace,
                jarFile = jarFile,
                dirInput = dirInput,
                rulesFile = rulesFile,
                outputFile = outputFile,
                reportFile = reportFile,
                maxTotalEntries = 2,
            )

        val error =
            shouldThrow<IllegalStateException> {
                task.generate()
            }

        error.message.orEmpty() shouldContain "Static baseline profile entry budget exceeded: actual=3, max=2"
        reportFile.readText() shouldContain "totalEntries=3"
        reportFile.readText() shouldContain "budgetStatus=FAIL"
    }

    test("generate resolves fallback outputs against configured project dir") {
        val workspace = prepareWorkspace("fallback-output")
        val jarFile = workspace.resolve("inputs/classes.jar").apply { writeJar(this, emptyMap()) }
        val dirInput = workspace.resolve("inputs/dir-classes").apply { mkdirs() }
        val rulesFile = workspace.resolve("baseline-rules.txt").apply { writeText("HSPL com/example/Foo") }
        val outputFile = workspace.resolve("__fallback_test_output__/generated.txt")
        val cwdFallbackFile = File("__fallback_test_output__/generated.txt")

        cwdFallbackFile.parentFile?.deleteRecursively()

        try {
            val task = createTask(workspace, jarFile, dirInput, rulesFile, outputFile)

            task.generate()

            outputFile.exists() shouldBe true
            outputFile.readText() shouldContain "Generated by static baseline profile task."
            cwdFallbackFile.exists() shouldBe false
        } finally {
            cwdFallbackFile.parentFile?.deleteRecursively()
        }
    }
})

private fun createTask(
    workspace: File,
    jarFile: File,
    dirInput: File,
    rulesFile: File,
    outputFile: File,
    reportFile: File = workspace.resolve("out/static-baseline-profile-report.txt"),
    maxTotalEntries: Int? = null,
): StaticBaselineProfileTask {
    val projectDir = workspace
    val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
    return project.tasks.create("generateStaticBaselineProfile", StaticBaselineProfileTask::class.java).apply {
        projectDirPath.set(project.projectDir.absolutePath)
        allJars.set(listOf(project.layout.projectDirectory.file(jarFile.relativeTo(projectDir).path)))
        allDirs.set(listOf(project.layout.projectDirectory.dir(dirInput.relativeTo(projectDir).path)))
        this.rulesFile.set(project.layout.projectDirectory.file(rulesFile.relativeTo(projectDir).path))
        outputProfile.set(project.layout.projectDirectory.file(outputFile.relativeTo(projectDir).path))
        this.reportFile.set(project.layout.projectDirectory.file(reportFile.relativeTo(projectDir).path))
        maxTotalEntries?.let { this.maxTotalEntries.set(it) }
    }
}

private fun prepareWorkspace(name: String): File =
    File("build/test-work/$name").apply {
        deleteRecursively()
        mkdirs()
    }

private fun writeJar(
    jarFile: File,
    entries: Map<String, ByteArray>,
) {
    jarFile.parentFile.mkdirs()
    JarOutputStream(FileOutputStream(jarFile)).use { jar ->
        entries.toSortedMap().forEach { (entryName, bytes) ->
            jar.putNextEntry(JarEntry(entryName))
            jar.write(bytes)
            jar.closeEntry()
        }
    }
}

private fun writeClassFile(
    dir: File,
    internalName: String,
    bytes: ByteArray,
) {
    val classFile = dir.resolve("$internalName.class")
    classFile.parentFile.mkdirs()
    classFile.writeBytes(bytes)
}

private fun classBytes(
    internalName: String,
    methods: List<MethodSpec>,
): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    writer.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
        internalName,
        null,
        "java/lang/Object",
        null,
    )
    methods.forEach { method ->
        val visitor =
            writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                method.name,
                method.descriptor,
                null,
                null,
            )
        emitMethodBody(visitor, method)
    }
    writer.visitEnd()
    return writer.toByteArray()
}

private fun emitMethodBody(
    visitor: MethodVisitor,
    method: MethodSpec,
) {
    visitor.visitCode()
    if (method.name == "<init>") {
        visitor.visitVarInsn(Opcodes.ALOAD, 0)
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        visitor.visitInsn(Opcodes.RETURN)
    } else {
        when (Type.getReturnType(method.descriptor).sort) {
            Type.VOID -> visitor.visitInsn(Opcodes.RETURN)
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                visitor.visitInsn(Opcodes.ICONST_0)
                visitor.visitInsn(Opcodes.IRETURN)
            }

            Type.LONG -> {
                visitor.visitInsn(Opcodes.LCONST_0)
                visitor.visitInsn(Opcodes.LRETURN)
            }

            Type.FLOAT -> {
                visitor.visitInsn(Opcodes.FCONST_0)
                visitor.visitInsn(Opcodes.FRETURN)
            }

            Type.DOUBLE -> {
                visitor.visitInsn(Opcodes.DCONST_0)
                visitor.visitInsn(Opcodes.DRETURN)
            }

            else -> {
                visitor.visitInsn(Opcodes.ACONST_NULL)
                visitor.visitInsn(Opcodes.ARETURN)
            }
        }
    }
    visitor.visitMaxs(0, 0)
    visitor.visitEnd()
}

private data class MethodSpec(
    val name: String,
    val descriptor: String,
)
