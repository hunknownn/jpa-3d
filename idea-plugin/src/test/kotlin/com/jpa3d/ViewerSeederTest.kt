package com.jpa3d

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [seedDetailJson] / [jsEscapeForSeed] 단위 테스트.
 *
 * 이 두 함수는 `jpa3d:add-seed` 이벤트의 detail JSON 을 만들어 [com.intellij.ui.jcef.JBCefBrowser]
 * 안에서 `executeJavaScript` 로 실행한다. 값(엔티티 FQN/패키지명)에 따옴표나 역슬래시가 들어가면
 * JS 문자열 리터럴이 깨지거나 임의 코드가 주입될 수 있으므로 이스케이프를 회귀 검증한다.
 */
class ViewerSeederTest {

    @Test
    fun `plain fqn produces well-formed detail json`() {
        assertEquals(
            """{"value":"com.example.User","type":"fqn"}""",
            seedDetailJson("com.example.User", "fqn")
        )
    }

    @Test
    fun `package seed keeps its type`() {
        assertEquals(
            """{"value":"com.example.domain","type":"package"}""",
            seedDetailJson("com.example.domain", "package")
        )
    }

    @Test
    fun `double quotes in value are escaped`() {
        // 깨진 결과: ...value":"a"b"... → JS 파싱 오류. 이스케이프되어야 한다.
        assertEquals(
            """{"value":"a\"b","type":"fqn"}""",
            seedDetailJson("""a"b""", "fqn")
        )
    }

    @Test
    fun `backslash is escaped before quotes so it does not double-escape`() {
        // 입력 `a\"b` → 역슬래시 먼저(`a\\"b`) 그다음 따옴표(`a\\\"b`).
        assertEquals("""a\\\"b""", jsEscapeForSeed("""a\"b"""))
    }

    @Test
    fun `value with no special chars is unchanged`() {
        assertEquals("com.example.User", jsEscapeForSeed("com.example.User"))
    }
}
