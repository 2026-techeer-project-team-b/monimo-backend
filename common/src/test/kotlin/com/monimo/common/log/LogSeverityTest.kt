package com.monimo.common.log

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

// 로그 등급 판정 한 곳(LogSeverity)의 규칙을 못박는 시험 (#236).
//
// 이 표를 보는 쪽이 둘이다 : 적재 처리기(LogRow.level, #144)와 수집기의 로그 하한(#215).
// 전에는 각자 판정해서 「숫자 0 + 글자 DEBUG」가 한쪽만 빠져나갔다. 그 갈림이 다시 생기면 여기가 먼저 빨개진다.
//
// 적재 처리기 쪽 기대는 LogTranslatorTest 가 proto 를 끼고 다시 본다 : 옮겨도 동작이 안 바뀌었다는 증거다
class LogSeverityTest : BehaviorSpec({

    Given("숫자가 규약 안(1~24)인 로그") {
        Then("여섯 구간이 각각 처음과 끝에서 같은 이름을 낸다") {
            listOf(1 to "TRACE", 4 to "TRACE", 5 to "DEBUG", 8 to "DEBUG", 9 to "INFO", 12 to "INFO").forEach { (n, name) ->
                LogSeverity.nameOf(n, "") shouldBe name
            }
            listOf(13 to "WARN", 16 to "WARN", 17 to "ERROR", 20 to "ERROR", 21 to "FATAL", 24 to "FATAL").forEach { (n, name) ->
                LogSeverity.nameOf(n, "") shouldBe name
            }
        }

        Then("글자가 어긋나도 숫자가 이긴다 (ADR #59 ③)") {
            LogSeverity.nameOf(17, "WARN") shouldBe "ERROR"
        }
    }

    Given("숫자가 없거나(0) 규약 밖인 로그") {
        Then("글자를 별칭 표로 본다") {
            LogSeverity.nameOf(0, "DEBUG") shouldBe "DEBUG"
            LogSeverity.nameOf(0, "SEVERE") shouldBe "ERROR"
            LogSeverity.nameOf(99, "WARNING") shouldBe "WARN"
            LogSeverity.nameOf(-1, "FINE") shouldBe "DEBUG"
            LogSeverity.nameOf(25, "DEBUG") shouldBe "DEBUG"
        }

        Then("이름만 보고 구간을 짐작하면 틀리는 자리들 (규약 부록 B 의 숫자를 따른다)") {
            LogSeverity.nameOf(0, "FINER") shouldBe "DEBUG"
            LogSeverity.nameOf(0, "FINEST") shouldBe "TRACE"
            LogSeverity.nameOf(0, "ALERT") shouldBe "ERROR"
            LogSeverity.nameOf(0, "CRIT") shouldBe "ERROR"
            LogSeverity.nameOf(0, "VERBOSE") shouldBe "DEBUG"
            LogSeverity.nameOf(0, "PANIC") shouldBe "ERROR"
            LogSeverity.nameOf(0, "DPANIC") shouldBe "ERROR"
            LogSeverity.nameOf(0, "CRITICAL") shouldBe "FATAL"
            LogSeverity.nameOf(0, "EMERGENCY") shouldBe "FATAL"
        }

        Then("앞뒤 공백과 대소문자는 안 가린다") {
            LogSeverity.nameOf(0, "  warning  ") shouldBe "WARN"
            LogSeverity.nameOf(0, "Info") shouldBe "INFO"
        }

        Then("표에 없는 글자와 빈 글자는 빈 글자로 둔다 (값 범위를 여섯 + 빈 글자로 닫는다)") {
            LogSeverity.nameOf(0, "MY_CUSTOM_LEVEL") shouldBe LogSeverity.UNKNOWN
            LogSeverity.nameOf(0, "") shouldBe LogSeverity.UNKNOWN
            LogSeverity.nameOf(99, "") shouldBe LogSeverity.UNKNOWN
        }
    }

    Given("구간 첫 번호 표") {
        Then("여섯 이름만 있고 값은 규약의 구간 시작이다") {
            LogSeverity.FIRST_NUMBER shouldBe mapOf(
                "TRACE" to 1,
                "DEBUG" to 5,
                "INFO" to 9,
                "WARN" to 13,
                "ERROR" to 17,
                "FATAL" to 21,
            )
        }

        Then("규약 안의 숫자 24개가 내는 이름은 모두 이 표에 있다 (하한 비교가 그 번호를 쓴다)") {
            (1..24).map { LogSeverity.nameOf(it, "") }.toSet() shouldBe LogSeverity.FIRST_NUMBER.keys
        }
    }
})
