package com.jpa3d

import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import com.jpa3d.settings.Jpa3dSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color

/** 현재 IDE 테마 모드 — 뷰어 UI 크롬이 IDE 다크/라이트를 따라가도록 전달하는 값. */
internal fun jpa3dThemeMode(): String = if (JBColor.isBright()) "light" else "dark"

/**
 * IDE 툴윈도우 콘텐츠(트리/리스트) 배경색 hex — 라이트 테마에서 뷰어 캔버스 배경을 맞추는 데 쓴다.
 *
 * Gradle/Project 같은 툴윈도우의 내용 영역은 트리라, 그 배경(라이트에선 거의 흰색)이
 * 사용자가 비교 대상으로 삼는 색이다. 패널 배경(getPanelBackground)은 더 칙칙한 쿨 그레이라
 * 비교 대상과 어긋나므로 트리 배경을 쓴다.
 */
internal fun jpa3dViewerBgHex(): String = colorHex(UIUtil.getTreeBackground())

private fun colorHex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

/**
 * 페이지 로드가 완료될 때마다 `window.__JPA3D_BRIDGE__` 를 주입한다.
 *
 * viewer 측 [api.ts] 가 이 글로벌을 통해 호스트(plugin) 에 요청을 전달한다:
 *   `window.__JPA3D_BRIDGE__.request("erd", {...}) -> Promise<unknown>`
 *
 * 구현 트릭:
 * 1. JBCefJSQuery 가 만든 JS 호출 스크립트(`jsQuery.inject(...)`) 는
 *    "콜백 매개변수 한 개를 받는 자바스크립트 함수의 본문" 형태를 요구한다.
 * 2. 그래서 페이로드는 `JSON.stringify` 로 직렬화해 한 줄 문자열로 넣고,
 *    호스트는 그걸 받아 다시 parse 한다.
 * 3. 호스트 응답도 JSON 문자열로 돌려주고, viewer 쪽에서 parse.
 */
class BridgeInjector(private val jsQuery: JBCefJSQuery) : CefLoadHandlerAdapter() {

    override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
        if (!frame.isMain) return

        // 콜백을 한 번 받아 Promise 로 감싸는 헬퍼를 자바스크립트로 정의
        val request = jsQuery.inject(
            /* queryResult = */ "JSON.stringify({kind: kind, args: args})",
            /* onSuccessCallback = */ "function(response) { resolve(JSON.parse(response)); }",
            /* onFailureCallback = */ "function(errorCode, errorMessage) { reject(new Error(errorMessage)); }"
        )

        // 설정(설정 → 도구 → JPA 3D)의 뷰어 기본값 — 툴윈도우를 fresh 로 열 때 viewer 의 초기 파라미터.
        val settings = Jpa3dSettings.getInstance()
        val defaultsJson = settings.viewerDefaultsJson()
        // 표시 언어 — override 모델(AUTO=IDE 로케일)로 결정된 코드를 뷰어 i18n 에 전달.
        val locale = settings.effectiveLanguageTag()
        // IDE 테마(다크/라이트)와 IDE 패널 배경색 — 뷰어 UI 크롬이 IDE 를 따라가도록 전달.
        // 페이지 로드 후의 IDE 테마 전환은 Jpa3dViewerPanel 의 LafManagerListener 가 push 한다.
        val theme = jpa3dThemeMode()
        val themeBg = jpa3dViewerBgHex()

        val script = """
            (function() {
              window.__JPA3D_DEFAULTS__ = $defaultsJson;
              window.__JPA3D_LOCALE__ = "$locale";
              window.__JPA3D_THEME__ = "$theme";
              window.__JPA3D_THEME_BG__ = "$themeBg";
              window.__JPA3D_BRIDGE__ = {
                request: function(kind, args) {
                  return new Promise(function(resolve, reject) {
                    $request
                  });
                }
              };
              // viewer 가 이미 mount 됐다면 강제로 한 번 다시 fetch 시키도록 이벤트 발행
              window.dispatchEvent(new CustomEvent("jpa3d:bridge-ready"));
            })();
        """.trimIndent()

        browser.executeJavaScript(script, frame.url, 0)
    }
}
