// Plain grpc-java + Netty. This module does not know about kotlinx-rpc:
// kotlinx-rpc picks it up through the grpc-java provider registries.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(libs.grpc.netty)
}
