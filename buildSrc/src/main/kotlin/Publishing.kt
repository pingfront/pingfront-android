import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension

/** The Maven group every SDK module is published under (the verified namespace of pingfront.sh on Maven Central). */
const val MAVEN_GROUP = "sh.pingfront"

/** The publishing repository that [registerMavenCentralUpload] bundles: `build/central/staging` of the root project. */
const val CENTRAL_STAGING = "centralStaging"

/** The version to publish: `-Ppingfront.version=…`, else a snapshot. */
fun Project.pingfrontVersion(): String = providers.gradleProperty("pingfront.version").getOrElse("1.0.0-SNAPSHOT")

/** True when this build publishes to [CENTRAL_STAGING]: the only time artifacts are signed. */
fun Project.publishesToCentral(): Boolean =
    gradle.taskGraph.allTasks.any { it.name.endsWith("To${CENTRAL_STAGING.replaceFirstChar(Char::uppercase)}Repository") }

/**
 * Maven publishing for an SDK module: the `release` variant (with sources and javadoc jars, configured in the
 * module's `android.publishing` block) as `sh.pingfront:<artifact>`, with the POM Maven Central requires.
 *
 * - `./gradlew publishToMavenLocal` puts every module in `~/.m2` for apps that build against a checkout
 *   (add `mavenLocal()` to their repositories). Nothing is signed.
 * - `centralStaging` (`build/central/staging`) is what `publishToMavenCentral` bundles and uploads
 *   (MavenCentral.kt). Only that publish is signed, with the GnuPG agent (`signing.gnupg.keyName` in
 *   `~/.gradle/gradle.properties`), so builds without a key keep working.
 *
 * The version is `pingfront.version` (default `1.0.0-SNAPSHOT`).
 */
fun Project.pingfrontPublishing(artifact: String, summary: String) {
    pluginManager.apply("maven-publish")
    pluginManager.apply("signing")
    afterEvaluate { project ->
        // Test fixtures are for the SDK's own tests: never published to customers.
        val component = project.components.getByName("release") as org.gradle.api.component.AdhocComponentWithVariants
        project.configurations.matching { it.name.contains("TestFixtures") && (it.name.endsWith("ApiPublication") || it.name.endsWith("RuntimePublication")) }.all { configuration ->
            component.withVariantsFromConfiguration(configuration) { it.skip() }
        }
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val release = publishing.publications.create("release", MavenPublication::class.java) { publication ->
            publication.from(project.components.getByName("release"))
            publication.groupId = MAVEN_GROUP
            publication.artifactId = artifact
            publication.version = project.pingfrontVersion()
            publication.pom { pom ->
                pom.name.set(artifact)
                pom.description.set(summary)
                pom.url.set("https://pingfront.sh")
                pom.licenses { licenses ->
                    licenses.license { license ->
                        license.name.set("MIT License")
                        license.url.set("https://opensource.org/license/mit")
                        license.distribution.set("repo")
                    }
                }
                pom.developers { developers ->
                    developers.developer { developer ->
                        developer.id.set("pingfront")
                        developer.name.set("pingfront")
                        developer.url.set("https://pingfront.sh")
                    }
                }
                pom.scm { scm ->
                    scm.url.set("https://github.com/pingfront/pingfront-android")
                    scm.connection.set("scm:git:https://github.com/pingfront/pingfront-android.git")
                    scm.developerConnection.set("scm:git:ssh://git@github.com/pingfront/pingfront-android.git")
                }
            }
        }
        publishing.repositories.maven { repo ->
            repo.name = CENTRAL_STAGING
            repo.url = project.rootProject.layout.buildDirectory.dir("central/staging").get().asFile.toURI()
        }
        project.extensions.getByType(SigningExtension::class.java).apply {
            useGpgCmd()
            sign(release)
            setRequired { project.publishesToCentral() }
        }
        // Without this a machine with a key configured would sign every local publish too.
        project.tasks.withType(Sign::class.java).configureEach { task -> task.onlyIf { project.publishesToCentral() } }
        val stage = project.tasks.named("publishReleasePublicationTo${CENTRAL_STAGING.replaceFirstChar(Char::uppercase)}Repository")
        stage.configure { it.mustRunAfter(":$CLEAN_CENTRAL_STAGING") }
        project.rootProject.tasks.named(CENTRAL_BUNDLE).configure { it.dependsOn(stage) }
    }
}
