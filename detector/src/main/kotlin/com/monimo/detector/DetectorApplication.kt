package com.monimo.detector

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 탐지
@SpringBootApplication
class DetectorApplication

fun main(args: Array<String>) {
    runApplication<DetectorApplication>(*args)
}
