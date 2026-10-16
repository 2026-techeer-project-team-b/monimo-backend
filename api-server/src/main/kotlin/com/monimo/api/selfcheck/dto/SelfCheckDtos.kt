package com.monimo.api.selfcheck.dto

// 파수꾼이 읽는 자기 점검 결과. 어느 항목이 ok=false 라도 HTTP 는 200 — 판단은 파수꾼이 한다
data class SelfCheckResponse(
    val postgres: PostgresCheck,
    val detector: AgeCheck,
    val collector: AgeCheck,
)

// SELECT 1 이 돌아오기까지 걸린 ms. 기한 안에 안 돌아왔으면 ok=false 에 error=timeout
data class PostgresCheck(
    val ok: Boolean,
    val ms: Long,
    val error: String? = null,
)

// 낡음 게이지(ADR #54). age_sec 가 null 이면 게이지를 못 읽은 것 (모듈이 죽었거나 주소가 틀림)
data class AgeCheck(
    val ok: Boolean,
    val ageSec: Double?,
    val maxSec: Long,
    val error: String? = null,
)
