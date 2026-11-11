plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.rpc)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.rpc.grpc.core)
    api(libs.kotlinx.rpc.protobuf)
}

rpc {
    protoc()
}
