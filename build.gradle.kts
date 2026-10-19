import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

group = "dev.liftonway"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Nullability from Spring's JSpecify annotations, as Spring Boot 4 recommends for Kotlin.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    // Spring Boot's BOM manages every version below that has none of its own.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(libs.postgres.binaries.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.springdoc.webmvc.ui)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    // `bootRun` starts a real PostgreSQL in-process (see DevDatabase); compiled against, never packaged.
    compileOnly(libs.embedded.postgres)
    developmentOnly(libs.embedded.postgres)
    developmentOnly(platform(libs.postgres.binaries.bom))

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.embedded.postgres)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// One runnable jar with a fixed name, for the Docker image.
tasks.jar { enabled = false }
tasks.bootJar { archiveFileName.set("liftonway-backend.jar") }

tasks.withType<Test> {
    useJUnitPlatform()
}

// `./gradlew bootRun` is local development: an embedded PostgreSQL, the fixed test code, demo rides.
tasks.bootRun {
    args("--spring.profiles.active=dev")
}
