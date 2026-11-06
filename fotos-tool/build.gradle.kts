plugins {
    application
}

description = "The local tool: drop a product list on it and get a folder of photos named by SKU."

dependencies {
    implementation(project(":fotos-core"))
    implementation(project(":fotos-stores"))
    // Without a logging backend slf4j prints a warning at start-up; this one writes to the console.
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.slf4j:slf4j-simple")
}

application {
    mainClass.set("com.axum.fotos.tool.Main")
    applicationName = "fotos-tool"
}

// The tool reports its version from the jar, so what it says is what was built.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
