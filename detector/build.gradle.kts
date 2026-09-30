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

tasks.withType<Test>().configureEach {
    // 스케줄 평가는 실제 시계로 돌며 API 서버를 부른다. 테스트는 끄고 EvaluationRunner.runOnce 를 직접 부른다
    systemProperty("monimo.alert.schedule.enabled", "false")
}
