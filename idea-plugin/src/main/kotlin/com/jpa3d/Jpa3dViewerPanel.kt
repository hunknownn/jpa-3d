package com.jpa3d

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * ToolWindow 안에 JCEF 로 viewer 를 띄우는 패널.
 *
 * 구성:
 * 1. plugin 리소스의 `web/index.html` 을 JCEF 로 로드
 * 2. JBCefJSQuery 로 viewer ↔ plugin 간 IPC 채널 구성
 * 3. 페이지 로드 완료 시 `window.__JPA3D_BRIDGE__` 를 주입 → viewer 의 api.ts 가 사용
 *
 * 실제 데이터 분석은 [Jpa3dRequestHandler] 가 처리한다. 지금은 스텁이라 빈 그래프만 반환.
 */
class Jpa3dViewerPanel(private val project: Project) : Disposable {

    private val log = logger<Jpa3dViewerPanel>()
    private val browser: JBCefBrowser?
    private val jsQuery: JBCefJSQuery?
    private val handler = Jpa3dRequestHandler(project)

    val component: JComponent

    init {
        if (!JBCefApp.isSupported()) {
            // 일부 환경(헤드리스, JBR 미장착 IDE) 에서는 JCEF 가 비활성. 안내 라벨로 대체.
            browser = null
            jsQuery = null
            component = JLabel("이 IDE 빌드는 JCEF 를 지원하지 않습니다.", SwingConstants.CENTER)
        } else {
            val b = JBCefBrowser()
            Disposer.register(this, b)
            browser = b

            val query = JBCefJSQuery.create(b as com.intellij.ui.jcef.JBCefBrowserBase)
            Disposer.register(this, query)
            jsQuery = query

            // viewer → plugin: postMessage 형태 JSON 을 받아 handler 에 위임
            query.addHandler { payload ->
                val response = handler.handle(payload)
                JBCefJSQuery.Response(response)
            }

            // 페이지 로드 완료 후 브리지 주입
            b.jbCefClient.addLoadHandler(BridgeInjector(query), b.cefBrowser)

            val url = viewerEntryUrl()
            if (url != null) {
                b.loadURL(url)
            } else {
                b.loadHTML(missingResourceHtml())
            }
            component = b.component
        }
    }

    /**
     * 리소스에 포함된 `web/index.html` 의 URL 을 돌려준다.
     * 빌드 시 `copyViewer` task 가 `viewer/dist` 를 여기로 복사한다.
     */
    private fun viewerEntryUrl(): String? {
        val resource = javaClass.classLoader.getResource("web/index.html") ?: return null
        log.info("viewer entry: $resource")
        return resource.toString()
    }

    private fun missingResourceHtml() = """
        <html><body style="background:#0f172a;color:#e2e8f0;font-family:sans-serif;padding:24px">
        <h3>viewer 리소스를 찾을 수 없습니다.</h3>
        <p><code>./gradlew :idea-plugin:copyViewer</code> 또는
        <code>cd viewer &amp;&amp; npm run build</code> 후 다시 빌드해 주세요.</p>
        </body></html>
    """.trimIndent()

    override fun dispose() {
        // 등록한 Disposer 체인이 정리해 줌
    }
}
