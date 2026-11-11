import kotlinx.validation.api.dump
import kotlinx.validation.api.filterOutAnnotated
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Builds the public API dump of a library's release classes with the
 * binary-compatibility-validator library (same format as its `apiDump`).
 */
@CacheableTask
abstract class ApiBuildTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun build() {
        // Compose compiler output (ComposableSingletons lambda holders) is not API; any lambda edit would churn the dump.
        val files = classes.asFileTree.matching { it.include("**/*.class") }.files
            .filterNot { it.name.startsWith("ComposableSingletons$") }
            .sortedBy { it.path }
        val signatures = files.asSequence().map { it.inputStream() }.loadApiFromJvmClasses()
            .filterOutNonPublic()
            .filterOutAnnotated(setOf("dev/pingfront/InternalPingFrontApi"))
        output.get().asFile.writeText(signatures.dump(StringBuilder()).toString())
    }
}

/** Fails when the checked-in dump differs from the current API. */
abstract class ApiCheckTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expected: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val actual: ConfigurableFileCollection

    @get:Input
    abstract val projectPath: Property<String>

    @TaskAction
    fun check() {
        val expectedFile = expected.singleOrNull()?.takeIf { it.exists() }
            ?: throw GradleException("No API dump for ${projectPath.get()}. Run ./gradlew ${projectPath.get()}:apiDump and commit it.")
        if (expectedFile.readText() != actual.singleFile.readText()) {
            throw GradleException(
                "The public API of ${projectPath.get()} changed. Review the diff of ${expectedFile.name}, " +
                    "then run ./gradlew ${projectPath.get()}:apiDump if the change is intended.",
            )
        }
    }
}

/** Copies the current dump over the checked-in one. */
abstract class ApiDumpTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val actual: ConfigurableFileCollection

    @get:Internal
    abstract val destination: RegularFileProperty

    @TaskAction
    fun dump() {
        val target = destination.get().asFile
        target.parentFile.mkdirs()
        actual.singleFile.copyTo(target, overwrite = true)
    }
}

/** Registers `apiBuild`, `apiCheck` (part of `check`) and `apiDump` for an Android library. */
fun Project.registerApiValidation() {
    val dumpFile = layout.projectDirectory.file("api/${project.name}.api")
    val build = tasks.register("apiBuild", ApiBuildTask::class.java) { task ->
        task.group = "verification"
        task.description = "Builds the public API dump of the release classes."
        task.classes.from(tasks.named("compileReleaseKotlin").map { it.outputs.files })
        task.output.set(layout.buildDirectory.file("api/${project.name}.api"))
    }
    val check = tasks.register("apiCheck", ApiCheckTask::class.java) { task ->
        task.group = "verification"
        task.description = "Checks the public API against api/${project.name}.api."
        task.expected.from(dumpFile)
        task.actual.from(build.flatMap { it.output })
        task.projectPath.set(project.path)
    }
    tasks.register("apiDump", ApiDumpTask::class.java) { task ->
        task.group = "verification"
        task.description = "Writes the public API to api/${project.name}.api."
        task.actual.from(build.flatMap { it.output })
        task.destination.set(dumpFile)
    }
    tasks.named("check") { it.dependsOn(check) }
}
