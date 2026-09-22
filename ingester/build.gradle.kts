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
    implementation(libs.spring.kafka)
    implementation(libs.clickhouse.client.v2)

    testImplementation(libs.bundles.service.test)
    testImplementation(libs.bundles.postgres.test)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.clickhouse)
    testRuntimeOnly(libs.junit.platform.launcher)
}
