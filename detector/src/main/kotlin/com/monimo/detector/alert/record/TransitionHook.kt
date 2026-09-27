package com.monimo.detector.alert.record

// 장애 재현 지점. 운영에서는 아무것도 하지 않고, 테스트가 "사건 저장 뒤 · outbox 저장 전" 실패를 끼워 넣는다
fun interface TransitionHook {
    fun afterEventSaved(alertEventId: Long)

    companion object {
        val NONE = TransitionHook { }
    }
}
