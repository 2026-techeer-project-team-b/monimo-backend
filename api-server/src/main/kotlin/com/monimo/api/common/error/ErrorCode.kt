package com.monimo.api.common.error

import org.springframework.http.HttpStatus

// API 명세 §0 에러 코드. 응답의 error.code 는 이 이름 그대로 나간다
enum class ErrorCode(
    val status: HttpStatus,
    val defaultMessage: String,
) {
    // 일반
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값의 모양이 올바르지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "현재 상태와 충돌합니다."),
    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_ENTITY, "요청을 처리할 수 없습니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "저장소가 응답하지 않습니다."),

    // 도메인
    CONFIG_VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 사람이 먼저 설정을 바꿨습니다."),
    APPLICATION_NAME_TAKEN(HttpStatus.CONFLICT, "이미 등록된 서비스 이름입니다."),
    RULE_CHANNEL_DUPLICATE(HttpStatus.CONFLICT, "이미 연결된 채널입니다."),
    SIGNAL_EXPIRED(HttpStatus.NOT_FOUND, "보관 기간이 지나 삭제된 데이터입니다."),
    TIME_RANGE_TOO_WIDE(HttpStatus.UNPROCESSABLE_ENTITY, "시간 범위가 상한을 넘었습니다."),
    AGENT_NOT_REACHABLE(HttpStatus.SERVICE_UNAVAILABLE, "파드에 연결된 수집기가 없습니다."),
    THREAD_DUMP_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "스레드 덤프 응답 시간이 초과됐습니다."),
}
