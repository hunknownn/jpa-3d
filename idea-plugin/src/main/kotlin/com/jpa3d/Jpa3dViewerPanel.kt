package com.jpa3d

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * ToolWindow 안에 JCEF 로 viewer 를 띄우는 패널.
 *
 * 핵심 설계:
 * - viewer 는 `vite-plugin-singlefile` 로 한 HTML 에 JS/CSS 가 모두 인라인된다.
 *   덕분에 외부 sub-resource 가 없어 origin 만 정해주면 끝.
 * - JBCefBrowser 의 2-arg `loadHTML(html, url)` 로 가짜 origin
 *   `http://jpa3d.local/` 를 부여 → `about:blank` 보다 fetch / postMessage 등이 안정적.
 * - 공식 JCEF 가이드의 `CefLocalRequestHandler` / `CefStreamResourceHandler` 는
 *   `org.intellij.images.editor.impl.jcef` 의 internal 클래스라 공개 API 가 아니다.
 *   본 plugin 처럼 단일 HTML 만 띄울 때는 이 우회가 더 깔끔하다.
 *
 * IPC:
 * - 페이지 로드 완료 시 [BridgeInjector] 가 `window.__JPA3D_BRIDGE__` 를 주입.
 * - viewer 측 호출은 [JBCefJSQuery] 를 통해 [Jpa3dRequestHandler] 로 전달된다.
 */
class Jpa3dViewerPanel(private val project: Project) : Disposable {

    private val log = logger<Jpa3dViewerPanel>()
    private val handler = Jpa3dRequestHandler(project)

    val component: JComponent

    init {
        if (!JBCefApp.isSupported()) {
            component = JLabel("이 IDE 빌드는 JCEF 를 지원하지 않습니다.", SwingConstants.CENTER)
        } else {
            val browser = JBCefBrowser()
            Disposer.register(this, browser)

            val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
            Disposer.register(this, jsQuery)

            // viewer → plugin
            jsQuery.addHandler { payload ->
                JBCefJSQuery.Response(handler.handle(payload))
            }

            // 페이지 로드 완료 후 브리지 주입
            browser.jbCefClient.addLoadHandler(BridgeInjector(jsQuery), browser.cefBrowser)

            val html = loadViewerHtml()
            if (html != null) {
                log.info("loading viewer (${html.length} chars) at $ORIGIN")
                browser.loadHTML(html, ORIGIN)
            } else {
                browser.loadHTML(missingResourceHtml())
            }
            component = browser.component
        }
    }

    private fun loadViewerHtml(): String? {
        val stream = javaClass.classLoader.getResourceAsStream("web/index.html") ?: return null
        return stream.use { it.readAllBytes() }.toString(Charsets.UTF_8)
    }

    private fun missingResourceHtml() = """
        <html><body style="background:#0f172a;color:#e2e8f0;font-family:sans-serif;padding:24px">
        <h3>viewer 리소스를 찾을 수 없습니다.</h3>
        <p><code>./gradlew :idea-plugin:copyViewer</code> 또는
        <code>cd viewer &amp;&amp; npm run build</code> 후 다시 빌드해 주세요.</p>
        </body></html>
    """.trimIndent()

    override fun dispose() {
        // Disposer 체인이 처리
    }

    companion object {
        // 가짜 origin — 실제 네트워크 호출은 발생하지 않지만 페이지 origin 으로 사용된다.
        private const val ORIGIN = "http://jpa3d.local/"
    }
}
