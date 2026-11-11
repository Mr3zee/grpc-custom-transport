plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.rpc)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":stdio-transport"))
    implementation(libs.kotlinx.rpc.grpc.client)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "demo.client.ClientMainKt"
}

// The tests start both apps as separate processes, so they need both start scripts.
val launchers = rootProject.layout.projectDirectory.let { root ->
    mapOf(
        "server.launcher" to root.file("server/build/install/server/bin/server").asFile.absolutePath,
        "client.launcher" to root.file("client/build/install/client/bin/client").asFile.absolutePath,
    )
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":server:installDist", "installDist")
    systemProperties(launchers)
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
