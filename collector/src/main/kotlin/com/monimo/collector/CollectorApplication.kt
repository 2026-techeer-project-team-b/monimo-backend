package com.monimo.collector

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 수집기
@SpringBootApplication
class CollectorApplication

fun main(args: Array<String>) {
    runApplication<CollectorApplication>(*args)
}
