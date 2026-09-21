plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// 공유 모델만 둔다. Entity · Repository · Service 금지.
dependencies {
    api(libs.opentelemetry.proto)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
