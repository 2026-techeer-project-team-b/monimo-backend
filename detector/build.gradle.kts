plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":common"))
    implementation(libs.bundles.service.base)

    testImplementation(libs.bundles.service.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
