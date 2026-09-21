import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

val javaVersion = libs.versions.java.get().toInt()

// MSA 경계: 모듈끼리는 :common 만 의존할 수 있다. 다른 모듈을 추가하면 빌드가 바로 실패한다.
val allowedProjectDependencies = setOf(":common")

subprojects {
    group = "com.monimo"
    version = "0.0.1-SNAPSHOT"

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(javaVersion)
            compilerOptions {
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
    }

    plugins.withId("org.springframework.boot") {
        // Docker 이미지에는 실행용 jar 하나만 필요하므로 일반 jar는 만들지 않는다.
        tasks.withType<Jar>().matching { it.name == "jar" }.configureEach { enabled = false }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    val modulePath = path
    configurations.configureEach {
        dependencies.withType<ProjectDependency>().configureEach {
            check(path in allowedProjectDependencies) {
                "모듈 경계 위반: $modulePath 가 $path 에 의존합니다. 모듈끼리는 :common 만 의존할 수 있습니다."
            }
        }
    }
}
