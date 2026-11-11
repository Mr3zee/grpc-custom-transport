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

// The client starts the server as a child JVM, so both tasks need the server's start script.
// `installDist` of :server writes it to the default location below.
val serverInstall = ":server:installDist"
val serverLauncher = rootProject.layout.projectDirectory.file("server/build/install/server/bin/server").asFile.absolutePath

tasks.named<JavaExec>("run") {
    dependsOn(serverInstall)
    args(serverLauncher)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(serverInstall)
    systemProperty("server.launcher", serverLauncher)
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
