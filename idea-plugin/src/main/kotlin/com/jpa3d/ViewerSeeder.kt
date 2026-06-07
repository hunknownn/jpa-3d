package com.jpa3d

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * JPA 3D 뷰어에 **중심(시드)** 을 추가하는 공통 진입점.
 *
 * 패키지 우클릭([Jpa3dAddPackageAction])과 엔티티 에디터 배너([com.jpa3d.export.EntitySqlBannerProvider])가
 * 같은 흐름을 공유한다:
 *  1. JPA 3D 툴윈도우를 activate (없으면 무시) — 콘텐츠(뷰어 패널) 생성 후 EDT 콜백에서 push.
 *  2. 살아있는 뷰어에 `jpa3d:add-seed` 이벤트를 dispatch. 툴윈도우를 새로 여는 경우
 *     React mount 전에 dispatch 될 수 있어, 브라우저 측 `__JPA3D_ADD_SEED_READY__` 플래그가
 *     설 때까지 폴링 후 dispatch 한다.
 *
 * 뷰어는 시드를 받으면 자동으로 center(seed) 범위로 전환한다(ErdApp.addSeed).
 */
object ViewerSeeder {

    private const val TOOL_WINDOW_ID = "JPA 3D"
    private val log = logger<ViewerSeeder>()

    /** 단일 엔티티를 FQN 시드로 추가. */
    fun addEntity(project: Project, fqn: String) = addSeed(project, fqn, "fqn")

    /** 패키지(+하위 전체)를 시드로 추가. */
    fun addPackage(project: Project, pkg: String) = addSeed(project, pkg, "package")

    /**
     * 시드 하나를 뷰어에 추가한다.
     *
     * @param type "fqn" | "package" — 뷰어의 SeedRef.type 과 동일.
     */
    fun addSeed(project: Project, value: String, type: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: run {
            log.warn("JPA 3D tool window not found")
            return
        }
        // activate 콜백은 콘텐츠(=뷰어 패널) 생성 후 EDT 에서 실행 → 이때 브라우저 핸들이 준비됨.
        toolWindow.activate({ push(project, value, type) }, /* autoFocusContents = */ true)
    }

    /** 살아있는 뷰어에 시드 추가 이벤트를 push (리스너 준비될 때까지 브라우저 측에서 폴링). */
    private fun push(project: Project, value: String, type: String) {
        val browser = project.service<Jpa3dBrowserHolder>().browser ?: run {
            log.warn("JPA 3D viewer browser not ready")
            return
        }
        val detail = """{"value":"${jsEscape(value)}","type":"${jsEscape(type)}"}"""
        val js = """
            (function() {
              var detail = $detail;
              var tries = 0;
              function go() {
                if (window.__JPA3D_ADD_SEED_READY__) {
                  window.dispatchEvent(new CustomEvent('jpa3d:add-seed', { detail: detail }));
                } else if (tries++ < 100) {
                  setTimeout(go, 50);
                }
              }
              go();
            })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, "", 0)
    }

    /** 자바스크립트 문자열 리터럴 안전 이스케이프 (역슬래시/따옴표). */
    private fun jsEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
