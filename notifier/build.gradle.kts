plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":common"))
    implementation(libs.bundles.service.base)
    implementation(libs.bundles.postgres)

    testImplementation(libs.bundles.service.test)
    testImplementation(libs.bundles.postgres.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
