package com.monimo.ingester

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 적재 처리기
@SpringBootApplication
class IngesterApplication

fun main(args: Array<String>) {
    runApplication<IngesterApplication>(*args)
}
