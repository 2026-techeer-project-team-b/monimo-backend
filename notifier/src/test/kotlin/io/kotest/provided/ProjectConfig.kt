package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.extensions.spring.SpringExtension

// Kotest 전체 설정. Kotest 6부터는 이 패키지(io.kotest.provided) 위치에 둬야 자동으로 읽힌다.
// SpringExtension: @SpringBootTest 컨텍스트 연결 + 테스트 클래스 생성자로 스프링 빈 주입
object ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(SpringExtension())
}
