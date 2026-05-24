package com.jpa3d

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

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
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
