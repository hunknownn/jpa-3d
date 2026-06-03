package com.jpa3d

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.jpa3d.export.ExportAction
import com.jpa3d.settings.Jpa3dSettings

/**
 * DumbAware: 인덱싱 중에도 ToolWindow 가 열려야 한다.
 * JCEF 뷰어 자체는 인덱스를 쓰지 않고, 분석기가 인덱스를 쓰게 되면
 * 그 시점에 [com.intellij.openapi.project.DumbService] 로 보호한다.
 */
class Jpa3dToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = Jpa3dViewerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        // panel 의 lifecycle 을 content 에 묶어 ToolWindow 닫힐 때 JCEF 도 정리되도록
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        // ToolWindow 상단 툴바 + 기어(더보기) 메뉴에 Export 액션 부착.
        // 액션 인스턴스 자체는 stateless 라 재사용 가능.
        val exportAction = ExportAction()
        toolWindow.setTitleActions(listOf(exportAction))
        toolWindow.setAdditionalGearActions(DefaultActionGroup(exportAction))

        // === 재오픈 시 뷰어 기본값 반영 ===
        // createToolWindowContent 는 최초 1회만 호출되고, 닫기(숨김)해도 JCEF 패널은 살아있어
        // 페이지 리로드가 일어나지 않는다. 그래서 설정(설정 → 도구 → JPA 3D)을 바꾼 뒤 재오픈해도
        // 살아있는 뷰어는 옛 상태 그대로다. 툴윈도우가 다시 표시될 때 현재 기본값을 push 해 반영한다.
        // 단, 단순 숨김/표시 반복으로 작업 화면이 초기화되지 않도록 **기본값이 직전과 달라졌을 때만** push.
        subscribeReapplyDefaults(project, panel)
    }

    /**
     * 툴윈도우가 (재)표시될 때, 설정의 뷰어 기본값이 직전 적용분과 다르면 살아있는 뷰어에 push.
     * 뷰어(ErdApp)는 `jpa3d:apply-defaults` 이벤트를 받아 파라미터를 기본값으로 리셋한다.
     */
    private fun subscribeReapplyDefaults(project: Project, panel: Jpa3dViewerPanel) {
        // 구독 시점(=최초 생성)의 기본값을 baseline 으로 둬, 첫 표시에서 불필요한 재적용을 막는다.
        var lastDefaults = Jpa3dSettings.getInstance().viewerDefaultsJson()
        project.messageBus.connect(panel).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id != TOOL_WINDOW_ID) return
                    val now = Jpa3dSettings.getInstance().viewerDefaultsJson()
                    if (now == lastDefaults) return
                    lastDefaults = now
                    val browser = project.service<Jpa3dBrowserHolder>().browser ?: return
                    val js = "window.__JPA3D_DEFAULTS__ = $now; " +
                        "window.dispatchEvent(new CustomEvent('jpa3d:apply-defaults'));"
                    browser.cefBrowser.executeJavaScript(js, "", 0)
                }
            }
        )
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    companion object {
        private const val TOOL_WINDOW_ID = "JPA 3D"
    }
}
