package com.jpa3d

import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

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

        val script = """
            (function() {
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
