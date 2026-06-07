package com.jpa3d

import com.jpa3d.settings.Jpa3dSettings
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/**
 * 플러그인 UI 문자열 번들.
 *
 * 표준 [com.intellij.DynamicBundle] 은 **IDE 로케일**만 따른다. 하지만 이 플러그인은
 * 자체 언어 설정(설정 → 도구 → JPA 3D, [Jpa3dSettings.UiLang])으로 IDE 와 무관하게
 * 언어를 강제(override)할 수 있어야 한다. 그래서 매 호출마다 [Jpa3dSettings.effectiveLocale]
 * 로 결정된 로케일의 [ResourceBundle] 을 직접 로드한다.
 *
 * - 번들 파일: `messages/Jpa3dBundle.properties`(기본=영어), `Jpa3dBundle_ko.properties`(한국어).
 *   JDK 9+ 는 `.properties` 를 UTF-8 로 읽으므로 한글을 그대로 쓸 수 있다.
 * - `ResourceBundle.getBundle` 결과는 JDK 가 내부 캐싱하므로 호출 비용은 낮다.
 * - 인자가 없으면 [MessageFormat] 을 거치지 않는다 → 아포스트로피(') 가 그대로 보존된다.
 *
 * plugin.xml 의 정적 `text=`/`description=` 는 플랫폼이 IDE 로케일로만 해석하므로,
 * 언어 설정을 따르게 하려는 액션은 `update()` 에서 이 번들로 텍스트를 다시 주입한다.
 */
object Jpa3dBundle {
    const val PATH = "messages.Jpa3dBundle"

    /**
     * **기본 로케일(OS/IDE) 로의 폴백을 끈** Control.
     *
     * 표준 `getBundle` 은 요청 로케일에 번들이 없으면 *JVM 기본 로케일* 번들로 먼저 폴백한다.
     * 그러면 예컨대 한국어 OS 에서 일본어 IDE(미지원) 를 쓰면 영어가 아니라 한국어가 나온다.
     * NoFallback 은 `요청 로케일 → ROOT(=영어 기본 번들)` 로만 떨어뜨려 "미지원 = 영어" 를 보장한다.
     */
    private val NO_FALLBACK =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    /** 지정 로케일의 번들 (미지원 로케일은 영어 기본). */
    fun bundle(locale: Locale): ResourceBundle = ResourceBundle.getBundle(PATH, locale, NO_FALLBACK)

    fun message(@PropertyKey(resourceBundle = PATH) key: String, vararg params: Any): String {
        val raw = bundle(Jpa3dSettings.getInstance().effectiveLocale()).getString(key)
        return if (params.isEmpty()) raw else MessageFormat.format(raw, *params)
    }
}
