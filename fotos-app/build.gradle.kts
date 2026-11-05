import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("org.springframework.boot") version "3.4.13"
    id("io.spring.dependency-management") version "1.1.7"
}

description = "REST API, store connectors and the embedded admin panel."

dependencies {
    implementation(project(":fotos-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Store connectors are tested against a real HTTP server, not an in-process mock: the
    // integration bugs live in the assembly (headers, status codes, odd bodies).
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

// Where the proxy hangs the app, seen from the BROWSER. The default '/admin' means direct access
// to Spring, with no nested proxy, so the external prefix is empty. Behind an IIS that hangs the
// app under a sub-path (-PpanelBasePath=/api/fotos/admin) the prefix is /api/fotos.
//
// Declared up here because more than one thing needs it: the panel (buildPanel below) and every
// absolute URL the app builds for itself, which break behind a nested proxy (docs/metodo/lecciones.md #19).
val panelBasePath = (project.findProperty("panelBasePath") as String?) ?: "/admin"
val externalBasePath = panelBasePath.removeSuffix("/admin")

/**
 * The commit this jar was built from, so /health reports it next to the version.
 *
 * The version is typed by a person and two different builds can carry the same one: on 2026-10-20
 * api-bonificaciones said 0.16.0 in production while running a jar older than the fix, and from
 * outside there was no way to tell. The hash comes from the repo and cannot be wrong. If the tree had uncommitted changes
 * it says so: a jar built from a dirty tree matches no commit.
 *
 * "+cambios" is kept in Spanish on purpose: it is the signal the deploy scripts and the people who
 * operate the store server already look for in the other services.
 *
 * Without git (a source copy without .git) it is "unknown" instead of breaking the build.
 */
val commitOfBuild: String = run {
    fun git(vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(rootDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    } catch (e: Exception) {
        null
    }
    val hash = git("rev-parse", "--short", "HEAD")
    when {
        hash.isNullOrEmpty() -> "unknown"
        !git("status", "--porcelain").isNullOrEmpty() -> "$hash+cambios"
        else -> hash
    }
}

/**
 * Replaces @version@, @commit@ and @externalBasePath@ in application.yml when packaging.
 *
 * ReplaceTokens and not expand(): expand uses Groovy templates and would break on the ${PORT:8082}
 * placeholders, which are Spring's and not the build's.
 */
tasks.named<ProcessResources>("processResources") {
    // Gradle does not track the value of a filter() as an input: without these lines a version bump
    // that does not touch the file leaves the task up to date and the jar keeps the OLD value.
    inputs.property("version", project.version)
    inputs.property("commit", commitOfBuild)
    inputs.property("externalBasePath", externalBasePath)
    filesMatching("application.yml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>(
            "tokens" to mapOf(
                "version" to project.version.toString(),
                "commit" to commitOfBuild,
                "externalBasePath" to externalBasePath))
    }
}

// The panel's basePath is baked into the HTML and JS at build time, so two builds with a different
// -PpanelBasePath are two different artifacts. Without the classifier both jars have the same name
// and a later local build silently overwrites the production jar that was already verified.
val isProductionBuild = panelBasePath != "/admin"

tasks.named<BootJar>("bootJar") {
    if (isProductionBuild) {
        archiveClassifier.set("prod")
        doFirst {
            // A production jar from a dirty tree reports a commit it does not match. -PallowDirtyBuild
            // is for checking the build locally (the simulator, a smoke test), never for delivery.
            if (commitOfBuild.endsWith("+cambios") && !project.hasProperty("allowDirtyBuild")) {
                throw GradleException(
                    "Refusing to build a PRODUCTION jar from a tree with uncommitted changes " +
                        "($commitOfBuild). Commit first, or pass -PallowDirtyBuild for a local check " +
                        "that will not be delivered.")
            }
        }
        doLast {
            logger.lifecycle("PRODUCTION jar (panelBasePath=$panelBasePath): ${archiveFile.get().asFile.name}")
        }
    } else {
        doLast {
            logger.lifecycle("LOCAL jar (panelBasePath=$panelBasePath): ${archiveFile.get().asFile.name} " +
                "-- do not use it for a deploy behind a nested proxy.")
        }
    }
}

// The panel (Next, static export) is embedded in the jar and served at /admin, in the same origin
// as the API: no CORS, no mixed content, and a single artifact to deploy.
//
// `gradlew build` REBUILDS the panel instead of copying whatever is in panel/out: in api-impuestos
// it only copied, so a panel change without a manual `npm run build` was invisible and the jar kept
// serving the old panel while the build and the tests were green.
//
// Without node_modules the task does not fail: the API is useful without the panel and the build
// cannot require Node on every machine. With node_modules present the panel is assumed to be part
// of the work, and a broken panel build breaks the build: visible is better than silent.
val panelSourceDir = layout.projectDirectory.dir("../panel")
val panelOutDir = panelSourceDir.dir("out")
val panelInstalled = panelSourceDir.dir("node_modules").asFile.exists()

val buildPanel by tasks.registering(Exec::class) {
    onlyIf {
        if (!panelInstalled) {
            logger.lifecycle("Panel: node_modules does not exist, not rebuilding (cd panel && npm ci first).")
        }
        panelInstalled
    }
    workingDir = panelSourceDir.asFile
    val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
    commandLine(npm, "run", "build")
    environment("PANEL_BASE_PATH", panelBasePath)
    inputs.dir(panelSourceDir.dir("app"))
    inputs.dir(panelSourceDir.dir("lib"))
    inputs.file(panelSourceDir.file("next.config.mjs"))
    inputs.file(panelSourceDir.file("package.json"))
    inputs.file(panelSourceDir.file("tsconfig.json"))
    inputs.property("panelBasePath", panelBasePath)
    outputs.dir(panelOutDir)
}

// Sync and not Copy: a Copy never deletes what was already in the destination, so the hashed chunks
// of previous builds would pile up forever and bloat the jar.
val copyPanel by tasks.registering(Sync::class) {
    dependsOn(buildPanel)
    from(panelOutDir)
    into(layout.buildDirectory.dir("resources/main/static/admin"))
    onlyIf {
        val exists = panelOutDir.asFile.exists()
        if (!exists) {
            logger.lifecycle("Panel not built (panel/out does not exist). To include it: cd panel && npm ci && npm run build")
        }
        exists
    }
}

tasks.named("classes") { dependsOn(copyPanel) }
