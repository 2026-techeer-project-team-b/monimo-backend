package com.monimo.ingester.transform

import com.google.protobuf.ByteString
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource
import java.time.Instant

// OTLP 값을 우리 모델 값으로 바꾸는 작은 도구들. 세 변환기(스팬 · 메트릭 · 로그)가 같이 쓴다.
//
// 클래스가 없고 함수만 있는 파일이다. 상태가 없으니 객체를 만들 이유가 없다.
// `internal` = 이 모듈(ingester) 안에서는 어디서나 쓰지만 다른 모듈에서는 안 보인다.
// private 이면 세 변환기가 못 쓰고, public 이면 OTLP 다루는 법이 모듈 밖으로 새 나간다. 그 중간이 internal.

// resource 속성에서 파드 식별자를 찾는 순서. 위에서부터 있는 것을 쓴다.
// service.instance.id 가 OTel 표준이고, 쿠버네티스에서는 보통 파드 이름이 들어간다
private val AGENT_ID_KEYS = listOf("service.instance.id", "k8s.pod.name", "host.name")

private const val SERVICE_NAME_KEY = "service.name"
private const val UNKNOWN_SERVICE = "unknown_service" // 에이전트가 이름을 안 넣으면 OTel 이 이렇게 보낸다

// 파드 환경 정보. agents 표의 사람이 보는 칸을 채운다 (없으면 null 로 두고 억지로 만들지 않는다)
private const val HOST_NAME_KEY = "host.name"
private const val JVM_VERSION_KEY = "process.runtime.version"
// OTel Java Agent 는 배포판 버전(distro)을 먼저 넣는다. 없으면 SDK 버전으로 대신한다
private val AGENT_VERSION_KEYS = listOf("telemetry.distro.version", "telemetry.sdk.version")

// resource(서비스 · 파드 단위 정보)에서 서비스 이름과 파드 식별자를 한 번에 꺼낸다.
// 3겹 구조(resource → scope → 기록)에서 resource 는 바깥에 한 번만 있으므로, 변환기는 이걸 한 번 꺼내 안쪽 기록들에 나눠 준다
internal data class Origin(val serviceName: String, val agentId: String)

internal fun Resource.origin(): Origin {
    val attributes = attributesList.toStringMap()
    return Origin(
        serviceName = attributes[SERVICE_NAME_KEY] ?: UNKNOWN_SERVICE,
        agentId = AGENT_ID_KEYS.firstNotNullOfOrNull { attributes[it] } ?: "",
    )
}

// OTLP 꼬리표는 값이 여러 타입(문자 · 숫자 · 참거짓 · 배열)이다. CH attributes 는 Map(String, String) 이라 글자로 맞춘다
internal fun List<KeyValue>.toStringMap(): Map<String, String> =
    associate { it.key to it.value.asString() }

internal fun AnyValue.asString(): String = when (valueCase) {
    AnyValue.ValueCase.STRING_VALUE -> stringValue
    AnyValue.ValueCase.BOOL_VALUE -> boolValue.toString()
    AnyValue.ValueCase.INT_VALUE -> intValue.toString()
    AnyValue.ValueCase.DOUBLE_VALUE -> doubleValue.toString()
    AnyValue.ValueCase.VALUE_NOT_SET -> ""
    // 배열 · 중첩 구조는 protobuf 가 준 글자 모양 그대로 넣는다. 화면에서 그대로 보여 주기만 한다
    else -> toString().trim()
}

// 16바이트 trace ID → 소문자 16진수 32글자, 8바이트 span ID → 16글자. 빈 값이면 빈 글자 (CH 관례: 없음은 NULL 이 아니라 '')
internal fun ByteString.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte) }

// OTLP 시각은 1970 년부터 흐른 나노초(Long) 하나다. Instant 로 바꾸면 초와 나노초가 갈라져 CH 가 받는 글자로 만들기 쉽다
internal fun Long.nanosToInstant(): Instant =
    Instant.ofEpochSecond(this / 1_000_000_000L, this % 1_000_000_000L)

// resource 에서 "이 파드를 봤다" 기록을 만든다. 서비스 이름과 파드 식별자는 origin() 과 같은 규칙으로 고른다.
// 적재할 줄을 만드는 것과 달리 이쪽은 PG agents 표에 올릴 재료라, 사람이 보는 환경 정보까지 같이 꺼낸다
internal fun Resource.sighting(seenAt: Instant): AgentSighting {
    val attributes = attributesList.toStringMap()
    return AgentSighting(
        // unknown_service 는 사람이 등록한 서비스 이름일 수 없으므로 빈 글자로 둬서 등록 대상에서 빠지게 한다
        serviceName = attributes[SERVICE_NAME_KEY]?.takeIf { it != UNKNOWN_SERVICE } ?: "",
        agentKey = AGENT_ID_KEYS.firstNotNullOfOrNull { attributes[it] } ?: "",
        hostname = attributes[HOST_NAME_KEY],
        jvmVersion = attributes[JVM_VERSION_KEY],
        agentVersion = AGENT_VERSION_KEYS.firstNotNullOfOrNull { attributes[it] },
        seenAt = seenAt,
    )
}
