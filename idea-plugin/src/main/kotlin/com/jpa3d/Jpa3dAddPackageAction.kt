package com.jpa3d

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPackage

/**
 * 프로젝트 트리에서 패키지를 우클릭 → "JPA 3D: 이 패키지 추가".
 *
 * 선택한 패키지를 JPA 3D 뷰어의 **중심(시드)** 으로 추가한다(다중 시드라 누적). 흐름:
 *  1. 선택에서 패키지 FQN 해석 (PsiPackage / PsiDirectory → JavaDirectoryService).
 *  2. JPA 3D 툴윈도우 activate (없으면 생성).
 *  3. 살아있는 뷰어에 `jpa3d:add-seed` 이벤트를 push.
 *     - 툴윈도우를 새로 여는 경우 React mount 전에 dispatch 될 수 있어,
 *       브라우저 쪽에서 `__JPA3D_ADD_SEED_READY__` 가 설 때까지 폴링 후 dispatch 한다.
 *
 * DumbAware — 인덱싱 중에도 메뉴는 뜬다. 뷰어 분석은 자체적으로 dumb mode 를 처리.
 */
class Jpa3dAddPackageAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val pkg = if (e.project != null) resolvePackageName(e) else null
        e.presentation.isEnabledAndVisible = pkg != null
        if (pkg != null) {
            // 짧은 패키지명을 텍스트에 노출 (full 은 description).
            e.presentation.text = "JPA 3D: '${pkg.substringAfterLast('.')}' 패키지 추가"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pkg = resolvePackageName(e) ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: run {
            log.warn("JPA 3D tool window not found")
            return
        }
        // activate 콜백은 콘텐츠(=뷰어 패널) 생성 후 EDT 에서 실행 → 이때 브라우저 핸들이 준비됨.
        toolWindow.activate({ pushAddSeed(project, pkg) }, /* autoFocusContents = */ true)
    }

    /** 살아있는 뷰어에 패키지 시드 추가 이벤트를 push (리스너 준비될 때까지 브라우저 측에서 폴링). */
    private fun pushAddSeed(project: com.intellij.openapi.project.Project, pkg: String) {
        val browser = project.service<Jpa3dBrowserHolder>().browser ?: run {
            log.warn("JPA 3D viewer browser not ready")
            return
        }
        val detail = """{"value":"${jsEscape(pkg)}","type":"package"}"""
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

    /**
     * 선택에서 패키지 FQN 을 해석한다. 패키지가 아니거나 기본(root) 패키지면 null.
     * PSI 접근이므로 read action 안에서 수행.
     */
    private fun resolvePackageName(e: AnActionEvent): String? {
        val project = e.project ?: return null
        val element = e.getData(CommonDataKeys.PSI_ELEMENT)
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        return ReadAction.compute<String?, RuntimeException> {
            when (element) {
                is PsiPackage -> return@compute element.qualifiedName.takeIf { it.isNotEmpty() }
                is PsiDirectory -> packageOf(element)?.let { return@compute it }
            }
            if (virtualFile != null && virtualFile.isDirectory) {
                val dir = PsiManager.getInstance(project).findDirectory(virtualFile)
                if (dir != null) return@compute packageOf(dir)
            }
            null
        }
    }

    private fun packageOf(dir: PsiDirectory): String? =
        JavaDirectoryService.getInstance().getPackage(dir)?.qualifiedName?.takeIf { it.isNotEmpty() }

    /** 자바스크립트 문자열 리터럴 안전 이스케이프 (역슬래시/따옴표). 패키지명엔 거의 없지만 방어적으로. */
    private fun jsEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TOOL_WINDOW_ID = "JPA 3D"
        private val log = logger<Jpa3dAddPackageAction>()
    }
}
