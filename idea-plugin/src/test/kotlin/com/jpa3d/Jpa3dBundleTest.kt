package com.jpa3d

import java.text.MessageFormat
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 메시지 번들 검증 — 플랫폼 의존 없는 순수 단위 테스트.
 *
 * [Jpa3dBundle.message] 는 앱 서비스([com.jpa3d.settings.Jpa3dSettings])에 의존해 단위 테스트에서
 * 호출할 수 없으므로, 실제와 같은 로더([Jpa3dBundle.bundle], NoFallback)로 번들을 직접 검증한다.
 *  1. `.properties` 가 UTF-8 로 읽혀 한글이 깨지지 않는지 (JDK 9+ 기본 동작).
 *  2. en/ko 키 집합이 정확히 일치하는지 (번역 누락/오타 방지).
 *  3. 미지원 로케일이 (OS 기본이 아니라) 영어 기본으로 폴백하는지.
 *  4. 파라미터 메시지가 [MessageFormat] 으로 올바르게 치환되는지.
 */
class Jpa3dBundleTest {

    private fun bundle(locale: Locale) = Jpa3dBundle.bundle(locale)

    @Test
    fun `korean values load as UTF-8`() {
        val ko = bundle(Locale.KOREAN)
        assertEquals("전체 모델", ko.getString("export.scope.all"))
        assertEquals("최소 한 가지 포맷을 선택해주세요.", ko.getString("export.validation.noFormat"))
        assertEquals("이 IDE 빌드는 JCEF 를 지원하지 않습니다.", ko.getString("viewer.jcefUnsupported"))
    }

    @Test
    fun `english is the default`() {
        val en = bundle(Locale.ENGLISH)
        assertEquals("Entire model", en.getString("export.scope.all"))
        // 미지원 로케일이면 영어 기본 번들로 폴백한다.
        val fr = bundle(Locale.FRENCH)
        assertEquals("Entire model", fr.getString("export.scope.all"))
    }

    @Test
    fun `en and ko have identical key sets`() {
        val en = bundle(Locale.ENGLISH).keySet()
        val ko = bundle(Locale.KOREAN).keySet()
        val missingInKo = en - ko
        val missingInEn = ko - en
        assertTrue(missingInKo.isEmpty(), "ko 에 없는 키: $missingInKo")
        assertTrue(missingInEn.isEmpty(), "en 에 없는 키: $missingInEn")
    }

    @Test
    fun `parameterized messages format correctly`() {
        val ko = bundle(Locale.KOREAN)
        // 단일 따옴표는 '' 로 이스케이프돼 한 개로 출력되고, {0} 는 인자로 치환된다.
        assertEquals(
            "JPA 3D: 'order' 패키지 추가",
            MessageFormat.format(ko.getString("action.addPackage.text"), "order")
        )
        assertEquals(
            "파일 3개 생성",
            MessageFormat.format(ko.getString("export.notify.filesCreated"), 3)
        )
    }
}
