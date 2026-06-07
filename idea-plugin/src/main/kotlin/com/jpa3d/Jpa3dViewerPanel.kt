package com.jpa3d

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefClient
import com.intellij.ui.jcef.JBCefJSQuery
import com.jpa3d.analyzer.Jpa3dAnalysisCache
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * ToolWindow 안에 JCEF 로 viewer 를 띄우는 패널.
 *
 * - viewer 는 vite-plugin-singlefile 로 한 HTML 에 인라인 → 외부 sub-resource 없음.
 * - `loadHTML(html, "http://jpa3d.local/")` 로 가짜 origin 부여 → fetch/postMessage 정상.
 * - 페이지 로드 완료 시 [BridgeInjector] 가 `window.__JPA3D_BRIDGE__` 주입.
 * - viewer 측 모든 호출은 [JBCefJSQuery] 를 통해 [Jpa3dRequestHandler] 로 전달.
 */
class Jpa3dViewerPanel(private val project: Project) : Disposable {

    private val log = logger<Jpa3dViewerPanel>()
    private val handler = Jpa3dRequestHandler(project)

    val component: JComponent

    init {
        if (!JBCefApp.isSupported()) {
            component = JLabel(Jpa3dBundle.message("viewer.jcefUnsupported"), SwingConstants.CENTER)
        } else {
            // JBCefClient 의 JS_QUERY_POOL_SIZE 는 브라우저 생성 *전* 에 설정해야 한다.
            // 기본값은 BridgeInjector 의 jsQuery 1개로 다 쓰여서, 그 후의 동적
            // JBCefJSQuery.create (Export snapshot 호출용) 가 IllegalStateException 으로
            // 실패해왔다. 16 슬롯이면 충분 (snapshot 호출은 1회당 슬롯 1개, 잠깐 점유 후 dispose).
            val client = JBCefApp.getInstance().createClient().apply {
                setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 16)
            }
            Disposer.register(this, client)
            val browser = JBCefBrowserBuilder().setClient(client).build()
            Disposer.register(client, browser)

            // 외부(Export 등) 가 viewer JS 를 호출할 수 있도록 핸들 등록. 패널 dispose 시 클리어.
            project.service<Jpa3dBrowserHolder>().browser = browser
            Disposer.register(this, Disposable {
                if (project.service<Jpa3dBrowserHolder>().browser === browser) {
                    project.service<Jpa3dBrowserHolder>().browser = null
                }
            })

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

            // === 캐시 워밍 ===
            // pooled thread 에서 인덱싱이 끝나길 기다린 뒤 한 번 분석을 돌려 캐시를 채워둔다.
            // 이렇게 하면 사용자가 동기화 버튼을 누를 때 (혹은 viewer 가 첫 fetchErd 를 보낼 때)
            // 캐시 히트라 즉시 응답 가능. JCEF 콜백 스레드에서 무거운 분석을 동기 수행하던
            // 부담이 사라져 IDE 메인 작업 영향을 최소화.
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    DumbService.getInstance(project).waitForSmartMode()
                    project.service<Jpa3dAnalysisCache>().getGraphData()
                    log.info("cache pre-warmed")
                } catch (e: Exception) {
                    log.warn("pre-warm failed: ${e.message}")
                }
            }
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
        private const val ORIGIN = "http://jpa3d.local/"
    }
}
