package com.monimo.collector.support

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

// 시험용 mTLS 인증서 (#159). compose 가 쓰는 scripts/certs/make-certs.sh 를 **같은 스크립트 그대로** 임시 폴더에 돌린다.
// 그래서 시험이 보는 인증서 모양(기관 · 수집기 · 손님 · 만료된 손님)이 로컬 compose 와 다를 수 없다.
//
// 레포에는 아무것도 남지 않는다 : JVM 이 끝날 때 임시 폴더를 지운다 (8절 "시험용 인증서는 임시 자리에 만들고 쓰고 지운다").
// openssl 이 PATH 에 있어야 한다 (macOS 기본 · ubuntu 러너 기본). 없으면 여기서 바로 실패한다
class TestCerts private constructor(val dir: File) {

    val ca: File = dir.resolve("ca.crt")
    val serverCertificate: File = dir.resolve("server.crt")
    val serverKey: File = dir.resolve("server.key")

    fun clientCertificate(name: String): File = dir.resolve("clients/$name.crt")
    fun clientKey(name: String): File = dir.resolve("clients/$name.key")

    companion object {
        // 우리 기관. 수집기와 손님이 같은 도장을 쓴다
        val ours: TestCerts by lazy { generate("monimo-certs-ours") }

        // 다른 기관. 도장이 다른 손님 = "우리 기관이 아닌 신분증" 시험 재료. 스크립트를 한 번 더 돌리면 기관이 새로 생긴다
        val other: TestCerts by lazy { generate("monimo-certs-other") }

        private fun generate(prefix: String): TestCerts {
            val rootDir = File(System.getProperty("monimo.rootDir") ?: error("monimo.rootDir 시스템 속성이 없다 (build.gradle.kts 가 넘긴다)"))
            val script = rootDir.resolve("scripts/certs/make-certs.sh")
            val dir = Files.createTempDirectory(prefix).toFile()
            Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })

            val process = ProcessBuilder("bash", script.absolutePath, dir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "시험용 인증서를 못 만들었다 (openssl 이 PATH 에 있어야 한다): $output"
            }
            return TestCerts(dir)
        }
    }
}
