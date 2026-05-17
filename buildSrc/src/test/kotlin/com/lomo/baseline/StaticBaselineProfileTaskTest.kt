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
 * Test Contract:
 * - Unit under test: StaticBaselineProfileTask in the build-system layer.
 * - Behavior focus: scan jar and directory class inputs, emit only matched class and method lines, keep output
 *   deterministic, and fail fast when the rules file is empty.
 * - Observable outcomes: generated profile file contents and IllegalArgumentException for an empty rules file.
 * - Red phase: Fails before the fix because StaticBaselineProfileTask does not exist yet and later because
 *   scanning, filtering, ordering, or empty-rule validation is missing or incorrect.
 * - Excludes: AndroidComponents wiring, release-variant registration, and app module contract coverage.
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
): StaticBaselineProfileTask {
    val projectDir = workspace
    val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
    return project.tasks.create("generateStaticBaselineProfile", StaticBaselineProfileTask::class.java).apply {
        projectDirPath.set(project.projectDir.absolutePath)
        allJars.set(listOf(project.layout.projectDirectory.file(jarFile.relativeTo(projectDir).path)))
        allDirs.set(listOf(project.layout.projectDirectory.dir(dirInput.relativeTo(projectDir).path)))
        this.rulesFile.set(project.layout.projectDirectory.file(rulesFile.relativeTo(projectDir).path))
        outputProfile.set(project.layout.projectDirectory.file(outputFile.relativeTo(projectDir).path))
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
