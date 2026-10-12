package com.monimo.collector.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar

// 수집기 TLS 파일 셋을 시험용 인증서(임시 폴더)로 가리킨다. 요구 수준(client-auth)은 각 시험이 @SpringBootTest 속성으로 준다.
// 파일 경로는 실행할 때 정해지므로 어노테이션에 못 적고 여기서 동적으로 넣는다 (TestInfraConfig 가 컨테이너 주소를 넣는 것과 같은 방식)
@TestConfiguration(proxyBeanMethods = false)
class TestCertsConfig {

    @Bean
    fun tlsProperties() = DynamicPropertyRegistrar { registry ->
        registry.add("monimo.collector.otlp.grpc.tls.server-certificate") { TestCerts.ours.serverCertificate.absolutePath }
        registry.add("monimo.collector.otlp.grpc.tls.server-key") { TestCerts.ours.serverKey.absolutePath }
        registry.add("monimo.collector.otlp.grpc.tls.ca-certificate") { TestCerts.ours.ca.absolutePath }
    }
}
