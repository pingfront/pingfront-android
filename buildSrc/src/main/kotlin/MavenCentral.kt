import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipFile
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip

const val CLEAN_CENTRAL_STAGING = "cleanCentralStaging"
const val CENTRAL_BUNDLE = "centralBundle"
const val PUBLISH_TO_CENTRAL = "publishToMavenCentral"

private const val UPLOAD_URL = "https://central.sonatype.com/api/v1/publisher/upload"

/**
 * Maven Central through the Central Publisher API (central.sonatype.org/publish/publish-portal-api), in plain Gradle:
 *
 * 1. `cleanCentralStaging` empties `build/central/staging`;
 * 2. every module publishes its signed `release` publication there (Publishing.kt), which also writes the `.md5`
 *    and `.sha1` checksums Central requires;
 * 3. `centralBundle` checks that every file has its signature and checksums and zips the folder in the Maven
 *    repository layout (`build/central/pingfront-android-<version>.zip`, without `maven-metadata.xml`);
 * 4. `publishToMavenCentral` uploads it with `publishingType=USER_MANAGED`: Central validates the deployment and
 *    waits for someone to press Publish in the portal (central.sonatype.com, Deployments).
 *
 * The token is base64(`mavenCentralUsername:mavenCentralPassword`), Gradle properties from
 * `~/.gradle/gradle.properties` (a Central Portal user token). `-PmavenCentralDryRun` does everything but the upload
 * and prints the request instead, without credentials. A `-SNAPSHOT` version is refused before anything runs.
 */
fun Project.registerMavenCentralUpload() {
    val version = pingfrontVersion()
    val staging = layout.buildDirectory.dir("central/staging")
    val dryRun = providers.gradleProperty("mavenCentralDryRun").isPresent

    val clean = tasks.register(CLEAN_CENTRAL_STAGING, Delete::class.java) { task ->
        task.group = "publishing"
        task.description = "Empties build/central/staging before a Maven Central bundle."
        task.delete(staging)
    }

    val bundle = tasks.register(CENTRAL_BUNDLE, Zip::class.java) { task ->
        task.group = "publishing"
        task.description = "Zips the signed modules in build/central/staging for the Central Publisher API."
        task.dependsOn(clean)
        task.from(staging) { it.exclude("**/maven-metadata*") }
        task.destinationDirectory.set(layout.buildDirectory.dir("central"))
        task.archiveFileName.set("pingfront-android-$version.zip")
        task.doFirst { checkStaging(staging.get().asFile) }
    }

    tasks.register(PUBLISH_TO_CENTRAL) { task ->
        task.group = "publishing"
        task.description = "Uploads every module to Maven Central for review in the portal (-PmavenCentralDryRun: build only)."
        task.dependsOn(bundle)
        val username = providers.gradleProperty("mavenCentralUsername")
        val password = providers.gradleProperty("mavenCentralPassword")
        task.doLast {
            val zip = bundle.get().archiveFile.get().asFile
            val url = "$UPLOAD_URL?publishingType=USER_MANAGED&name=" + URLEncoder.encode("pingfront-android $version", Charsets.UTF_8).replace("+", "%20")
            val entries = ZipFile(zip).use { it.size() }
            if (dryRun) {
                task.logger.lifecycle(
                    "Dry run, nothing uploaded. The upload would be:\n" +
                        "  POST $url\n" +
                        "  Authorization: Bearer base64(mavenCentralUsername:mavenCentralPassword)\n" +
                        "  multipart/form-data, part \"bundle\": ${zip.name} (application/octet-stream, ${zip.length()} bytes, $entries files)\n" +
                        "  bundle: ${zip.path}",
                )
                return@doLast
            }
            val user = username.orNull
            val pass = password.orNull
            if (user.isNullOrBlank() || pass.isNullOrBlank()) {
                throw GradleException(
                    "Set mavenCentralUsername and mavenCentralPassword (a Central Portal user token) in ~/.gradle/gradle.properties.",
                )
            }
            val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
            val boundary = "pingfront-" + UUID.randomUUID()
            val head = "--$boundary\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"${zip.name}\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            val body = head.toByteArray() + zip.readBytes() + "\r\n--$boundary--\r\n".toByteArray()
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
                .send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 201) {
                throw GradleException("Maven Central refused the upload (HTTP ${response.statusCode()}): ${response.body().take(2000)}")
            }
            task.logger.lifecycle(
                "Uploaded pingfront-android $version to Maven Central, deployment ${response.body().trim()}.\n" +
                    "Review it at https://central.sonatype.com/publishing/deployments and press Publish once it is validated.",
            )
        }
    }

    gradle.taskGraph.whenReady { graph ->
        val central = graph.allTasks.any { it.path == ":$CENTRAL_BUNDLE" || it.path == ":$PUBLISH_TO_CENTRAL" }
        if (central && version.endsWith("-SNAPSHOT")) {
            throw GradleException(
                "Maven Central does not accept snapshots ($version). Pass the release version: ./gradlew $PUBLISH_TO_CENTRAL -Ppingfront.version=1.0.0",
            )
        }
    }
}

/** Every artifact in the staging folder has its `.asc` signature and its `.md5` and `.sha1` checksums. */
private fun checkStaging(root: File) {
    val files = root.walk().filter { it.isFile && !it.name.startsWith("maven-metadata") }.toList()
    val checksum = Regex("""\.(md5|sha1|sha256|sha512)$""")
    val artifacts = files.filter { !checksum.containsMatchIn(it.name) && !it.name.endsWith(".asc") }
    if (artifacts.isEmpty()) throw GradleException("Nothing was published to ${root.path}.")
    val missing = artifacts.flatMap { artifact ->
        listOf(".asc", ".md5", ".sha1").map { File(artifact.path + it) }.filterNot { it.isFile }
    }
    if (missing.isNotEmpty()) {
        throw GradleException("The Central bundle is incomplete:\n  " + missing.joinToString("\n  ") { it.relativeTo(root).path })
    }
}
