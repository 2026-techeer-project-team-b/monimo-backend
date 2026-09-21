rootProject.name = "monimo-backend"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // 팀원 컴퓨터에 JDK 17이 없으면 Gradle이 자동으로 내려받는다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "common",
    "collector",
    "ingester",
    "api-server",
    "detector",
    "notifier",
)
