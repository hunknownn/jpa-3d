package com.jpa3d.export

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.jpa3d.Jpa3dBundle
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Plugin → viewer 로 JS 를 실행해 결과를 수거하는 어댑터.
 *
 * 플로우:
 *   1. JBCefJSQuery 를 한 개 만들고 핸들러로 결과를 받을 future 를 준비
 *   2. viewer 의 비동기 함수(`window.__JPA3D_SNAPSHOT__` 등) 호출 후
 *      query.inject(...) 로 만들어진 콜백 JS 를 통해 결과 회송
 *   3. future.get(timeout) 으로 동기 대기
 *
 * 호출 측은 EDT 가 아닌 백그라운드 스레드에서 사용해야 한다 (future.get 으로 블로킹하므로).
 */
class ViewerSnapshot(private val browser: JBCefBrowser) {

    private val log = logger<ViewerSnapshot>()
    private val mapper = ObjectMapper()

    /** PNG 는 byte[], SVG 는 String → ByteArray(UTF-8). error 가 채워지면 실패. */
    data class Result(
        val mime: String,
        val bytes: ByteArray,
        val error: String? = null
    )

    /** "2d" / "3d" / null(질의 실패). */
    fun queryViewMode(timeoutSec: Long = 3): String? {
        val payload = executeAndAwait(
            jsExpression = "JSON.stringify({mode: window.__JPA3D_VIEW_STATE__ && window.__JPA3D_VIEW_STATE__().mode})",
            timeoutSec = timeoutSec
        ) ?: return null
        return try {
            mapper.readTree(payload).path("mode").asText(null)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            log.warn("queryViewMode parse failed: ${e.message}")
            null
        }
    }

    /**
     * format = "png" / "svg".
     * 반환값의 `error` 가 비어있지 않으면 bytes 는 빈 배열.
     */
    fun capture(format: String, timeoutSec: Long = 15): Result {
        val payload = executeAndAwait(
            // Promise 결과를 await 한 뒤 JSON 으로 직렬화. await 가 가능한 IIFE.
            jsExpression = """(async function(){
                try {
                    const r = await window.__JPA3D_SNAPSHOT__('$format');
                    return JSON.stringify(r);
                } catch (e) {
                    return JSON.stringify({format: '$format', error: String(e && e.message || e)});
                }
            })()""",
            awaitPromise = true,
            timeoutSec = timeoutSec
        ) ?: return Result("", ByteArray(0), Jpa3dBundle.message("snapshot.error.noResponse"))

        return try {
            val node = mapper.readTree(payload)
            val err = node.path("error").asText("").takeIf { it.isNotEmpty() }
            if (err != null) return Result("", ByteArray(0), err)
            val data = node.path("data").asText("")
            if (data.isEmpty()) return Result("", ByteArray(0), Jpa3dBundle.message("snapshot.error.emptyData"))
            when (format) {
                "png" -> {
                    // data URL: "data:image/png;base64,...."
                    val b64 = data.substringAfter(",", "")
                    Result("image/png", Base64.getDecoder().decode(b64))
                }
                "svg" -> Result("image/svg+xml", data.toByteArray(Charsets.UTF_8))
                else -> Result("", ByteArray(0), Jpa3dBundle.message("snapshot.error.unsupportedFormat", format))
            }
        } catch (e: Exception) {
            log.warn("capture parse failed: ${e.message}", e)
            Result("", ByteArray(0), Jpa3dBundle.message("snapshot.error.parseFailed", e.message ?: ""))
        }
    }

    /**
     * JS 표현식을 실행하고 결과 문자열을 반환. `awaitPromise=true` 면 표현식이 Promise<string>
     * 를 반환한다고 가정하고 await 한다.
     *
     * JBCefJSQuery 생성 / executeJavaScript / dispose 는 모두 EDT 에서 호출한다.
     * CEF message router 가 EDT 컨텍스트에 바인딩돼 있어, off-EDT 에서 시도하면 콜백이
     * 누락되는 사례가 있었다 (타임아웃으로 보고됨).
     */
    private fun executeAndAwait(
        jsExpression: String,
        awaitPromise: Boolean = false,
        timeoutSec: Long = 10
    ): String? {
        val future = CompletableFuture<String>()
        val queryHolder = arrayOfNulls<JBCefJSQuery>(1)

        ApplicationManager.getApplication().invokeAndWait {
            try {
                val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
                queryHolder[0] = query
                query.addHandler { payload ->
                    future.complete(payload ?: "")
                    JBCefJSQuery.Response(null)
                }
                val core = if (awaitPromise) "var result = await ($jsExpression);" else "var result = ($jsExpression);"
                val callback = query.inject(
                    /* queryResult = */ "result",
                    /* onSuccessCallback = */ "function(){}",
                    /* onFailureCallback = */ "function(){ window.cefQuery && console && console.warn && console.warn('jpa3d cef onFailure'); }"
                )
                val script = """
                    (async function() {
                      try {
                        $core
                        $callback
                      } catch (e) {
                        var result = JSON.stringify({error: String(e && e.message || e)});
                        $callback
                      }
                    })();
                """.trimIndent()
                browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url ?: "", 0)
            } catch (e: Exception) {
                log.warn("viewer JS setup 실패: ${e.message}", e)
                future.completeExceptionally(e)
            }
        }

        try {
            return future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            log.warn("viewer JS 응답 타임아웃 (${timeoutSec}s)")
            return null
        } catch (e: Exception) {
            log.warn("viewer JS 실행 실패: ${e.message}", e)
            return null
        } finally {
            queryHolder[0]?.let { q ->
                ApplicationManager.getApplication().invokeLater { Disposer.dispose(q) }
            }
        }
    }
}
