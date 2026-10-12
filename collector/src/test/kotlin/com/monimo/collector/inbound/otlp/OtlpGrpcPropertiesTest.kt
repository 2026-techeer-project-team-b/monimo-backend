package com.monimo.collector.inbound.otlp

import com.monimo.collector.support.TestCerts
import io.grpc.InsecureServerCredentials
import io.grpc.TlsServerCredentials
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.BindMethod
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

// TLS 설정 묶기와 자격증명 만들기 (#159). 스프링을 띄우지 않는다.
// 요구 수준 글자 셋이 grpc ClientAuth 셋으로 가고, 모르는 글자는 기동이 실패하며, 켜 놓고 파일이 없으면 평문으로 대신 뜨지 않는다
class OtlpGrpcPropertiesTest : BehaviorSpec({

    fun bind(vararg entries: Pair<String, String>): OtlpGrpcProperties =
        Binder(MapConfigurationPropertySource(entries.toMap()))
            .bind("monimo.collector.otlp.grpc", Bindable.of(OtlpGrpcProperties::class.java).withBindMethod(BindMethod.VALUE_OBJECT))
            .orElse(OtlpGrpcProperties()) // 아무것도 안 적으면 묶을 것이 없어 기본값 그대로다

    Given("요구 수준 설정 글자") {
        When("아무것도 안 적으면") {
            Then("none 이고 평문이다 : 받은 사람이 아무것도 안 해도 지금처럼 돈다") {
                val properties = bind()
                properties.tls.clientAuth shouldBe OtlpGrpcProperties.ClientAuth.NONE
                serverCredentialsOf(properties.tls).shouldBeInstanceOf<InsecureServerCredentials>()
            }
        }

        When("none · optional · require 를 적으면") {
            Then("grpc 의 ClientAuth 세 값에 그대로 간다 (대소문자 무관)") {
                bind("monimo.collector.otlp.grpc.tls.client-auth" to "none").tls.clientAuth.toGrpc() shouldBe TlsServerCredentials.ClientAuth.NONE
                bind("monimo.collector.otlp.grpc.tls.client-auth" to "optional").tls.clientAuth.toGrpc() shouldBe TlsServerCredentials.ClientAuth.OPTIONAL
                bind("monimo.collector.otlp.grpc.tls.client-auth" to "require").tls.clientAuth.toGrpc() shouldBe TlsServerCredentials.ClientAuth.REQUIRE
                bind("monimo.collector.otlp.grpc.tls.client-auth" to "REQUIRE").tls.clientAuth shouldBe OtlpGrpcProperties.ClientAuth.REQUIRE
            }
        }

        When("모르는 글자를 적으면") {
            Then("묶는 단계에서 실패한다 : 조용히 기본값으로 떨어지지 않는다") {
                shouldThrow<BindException> { bind("monimo.collector.otlp.grpc.tls.client-auth" to "required") }
            }
        }
    }

    Given("자격증명 만들기") {
        val certs = TestCerts.ours

        When("require 인데 파일 경로를 하나도 안 줬으면") {
            Then("기동이 실패한다. 어떤 파일이 비었는지 말한다") {
                val error = shouldThrow<IllegalStateException> {
                    serverCredentialsOf(OtlpGrpcProperties.Tls(clientAuth = OtlpGrpcProperties.ClientAuth.REQUIRE))
                }
                error.message shouldContain "server-certificate"
                error.message shouldContain "server-key"
                error.message shouldContain "ca-certificate"
                error.message shouldContain "평문으로 대신 뜨지 않는다"
            }
        }

        When("optional 인데 기관 인증서 경로가 없는 파일을 가리키면") {
            Then("기동이 실패한다. 그 파일 하나만 말한다") {
                val error = shouldThrow<IllegalStateException> {
                    serverCredentialsOf(
                        OtlpGrpcProperties.Tls(
                            clientAuth = OtlpGrpcProperties.ClientAuth.OPTIONAL,
                            serverCertificate = certs.serverCertificate.absolutePath,
                            serverKey = certs.serverKey.absolutePath,
                            caCertificate = certs.dir.resolve("없는-파일.crt").absolutePath,
                        ),
                    )
                }
                error.message shouldContain "없는-파일.crt"
                error.message!! shouldNotContain "server-key="
            }
        }

        When("none 인데 파일 경로가 없는 파일을 가리키면") {
            Then("무시하고 평문이다 : 꺼져 있으면 파일을 읽지 않는다") {
                serverCredentialsOf(
                    OtlpGrpcProperties.Tls(
                        clientAuth = OtlpGrpcProperties.ClientAuth.NONE,
                        serverCertificate = "/없는/파일.crt",
                    ),
                ).shouldBeInstanceOf<InsecureServerCredentials>()
            }
        }

        When("require 이고 파일 셋이 다 읽히면") {
            Then("TLS 자격증명이 만들어진다") {
                serverCredentialsOf(
                    OtlpGrpcProperties.Tls(
                        clientAuth = OtlpGrpcProperties.ClientAuth.REQUIRE,
                        serverCertificate = certs.serverCertificate.absolutePath,
                        serverKey = certs.serverKey.absolutePath,
                        caCertificate = certs.ca.absolutePath,
                    ),
                ).shouldBeInstanceOf<TlsServerCredentials>()
            }
        }
    }
})

