package com.jpa3d

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * viewer → plugin 요청 처리기. JSON 문자열을 받아 JSON 문자열을 돌려준다.
 *
 * 현재는 스텁: 분석기가 아직 없으므로 빈 그래프 / 빈 검색 결과만 반환한다.
 * 다음 단계에서 PSI 기반 분석기 [Jpa3dPsiAnalyzer] 가 채울 자리.
 */
class Jpa3dRequestHandler(@Suppress("unused") private val project: Project) {

    private val log = logger<Jpa3dRequestHandler>()

    fun handle(payload: String): String {
        // payload 예: {"kind":"erd","args":{"scope":"all","level":1}}
        log.info("bridge request: $payload")

        // 간단한 kind 만 분기 — JSON 라이브러리 의존을 늘리지 않기 위해 substring 매칭.
        // 진짜 구현에서는 Jackson / kotlinx-serialization 으로 교체.
        return when {
            payload.contains("\"kind\":\"erd\"") -> EMPTY_ERD_JSON
            payload.contains("\"kind\":\"search\"") -> EMPTY_LIST_JSON
            else -> EMPTY_LIST_JSON
        }
    }

    companion object {
        private const val EMPTY_ERD_JSON = """{"seed":"","depth":0,"nodes":[],"links":[]}"""
        private const val EMPTY_LIST_JSON = "[]"
    }
}
