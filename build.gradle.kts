plugins {
    // `base` and not `java-library`: the root project has no code of its own, it only groups the
    // modules. java-library would also package an empty jar named like the real one, which already
    // caused the wrong jar to be deployed in api-impuestos.
    base
}

allprojects {
    group = "com.axum.fotos"
    version = "0.4.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // Sources are UTF-8. Without this javac uses the machine default (windows-1252 on Windows) and
    // silently mangles accents in strings and comments.
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Tests tagged "live" hit the real store catalogs. They are excluded by default so that
            // `gradlew build` stays green on any machine, without network and without hammering
            // third parties. Run them with -PincludeLiveTests.
            if (!project.hasProperty("includeLiveTests")) {
                excludeTags("live")
            }
            // Tests tagged "db" need a local SQL Server with the database created.
            // Run them with -PincludeDbTests.
            if (!project.hasProperty("includeDbTests")) {
                excludeTags("db")
            }
        }
        if (project.hasProperty("includeLiveTests")) {
            testLogging { showStandardStreams = true }
        }
    }
}

// The version lives in two places: here and in .claude/VERSION, which the audit hook reads. Nothing
// syncs them on its own, so the build fails when they differ instead of letting them drift.
val checkVersionFile by tasks.registering {
    val versionFile = layout.projectDirectory.file(".claude/VERSION")
    val projectVersion = project.version.toString()
    inputs.file(versionFile)
    inputs.property("version", projectVersion)
    doLast {
        val declared = versionFile.asFile.readText().trim()
        if (declared != projectVersion) {
            throw GradleException(
                ".claude/VERSION says '$declared' but build.gradle.kts says '$projectVersion'. " +
                    "Bump both together, in the same commit.")
        }
    }
}

tasks.named("check") { dependsOn(checkVersionFile) }
