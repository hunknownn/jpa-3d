package com.jpa3d

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class Jpa3dToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = Jpa3dViewerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        // panel 의 lifecycle 을 content 에 묶어 ToolWindow 닫힐 때 JCEF 도 정리되도록
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
