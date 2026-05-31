package com.jpa3d

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.jpa3d.export.ExportAction

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
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
