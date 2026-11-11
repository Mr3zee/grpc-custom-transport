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
    implementation(libs.kotlinx.rpc.grpc.server)
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass = "demo.server.ServerMainKt"
}
