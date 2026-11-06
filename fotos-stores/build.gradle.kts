description = "Store connectors (VTEX by EAN) with pacing, retry and circuit breaker. No Spring Boot: usable from the service and from the local tool."

dependencies {
    // Versions come from the Spring Boot BOM, so this module and fotos-app never disagree on them.
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.13"))
    api(project(":fotos-core"))
    api("org.springframework:spring-web")
    api("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}
