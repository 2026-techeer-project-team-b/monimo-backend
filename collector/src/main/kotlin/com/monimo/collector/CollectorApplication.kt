package com.monimo.collector

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// 수집기
//
// @EnableScheduling 은 샘플링 비율을 PG 에서 주기로 읽기 위한 것이다 (ADR #53).
// 읽기를 OTLP 요청 경로에서 하면 PG 가 죽었을 때 그 요청이 연결 대기만큼 멈춘다 (실측 10초).
@SpringBootApplication
@EnableScheduling
class CollectorApplication

fun main(args: Array<String>) {
    runApplication<CollectorApplication>(*args)
}
