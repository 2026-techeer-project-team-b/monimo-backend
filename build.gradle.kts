import org.jetbrains.kotlin.allopen.gradle.AllOpenExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
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

    plugins.withId("org.jetbrains.kotlin.plugin.jpa") {
        // Entity를 open 으로 만들어 지연 로딩 프록시가 동작하게 한다 (ADR #42 가드레일 ④, data class 금지)
        extensions.configure<AllOpenExtension> {
            annotation("jakarta.persistence.Entity")
            annotation("jakarta.persistence.MappedSuperclass")
            annotation("jakarta.persistence.Embeddable")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // 서비스는 평소 마이그레이션을 안 돌리지만(ADR #49), 테스트에서는 db/postgres 를 그대로 적용해
        // Entity와 표가 맞는지 검증한다. 스프링이 뜨기 전에 정해져야 해서 테스트 JVM 옵션으로 넘긴다.
        systemProperty("spring.flyway.enabled", "true")
        systemProperty("spring.flyway.locations", "filesystem:${rootDir}/db/postgres")
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
