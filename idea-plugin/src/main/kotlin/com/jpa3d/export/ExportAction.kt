package com.jpa3d.export

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * "JPA 3D → Export..." 액션.
 *
 * plugin.xml 에 ToolWindow 툴바 / 더보기 / Tools 메뉴 3곳에 동일 액션을 등록한다.
 * DumbAware — 인덱싱 중에도 다이얼로그 자체는 열린다. 실행 시점에 분석 캐시가 dumb
 * mode 라면 빈 그래프가 export 될 수 있다(사용자에게 알림으로 안내).
 */
class ExportAction : AnAction("Export...", "JPA 3D 모델을 파일로 export", AllIcons.ToolbarDecorator.Export), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = ExportDialog(project)
        if (!dialog.showAndGet()) return
        val options = dialog.collectOptions()
        runExport(project, options)
    }

    private fun runExport(project: Project, options: ExportOptions) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "JPA 3D export", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "그래프 변환 중..."
                val result = ExportRunner(project).run(options)
                notify(project, result)
            }
        })
    }

    private fun notify(project: Project, result: ExportRunner.Result) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        val msg = buildString {
            append("파일 ${result.writtenFiles.size}개 생성")
            if (result.skippedFormats.isNotEmpty()) {
                append("\n스킵: ${result.skippedFormats.joinToString(", ")}")
            }
            if (result.writtenFiles.isNotEmpty()) {
                append("\n위치: ${result.writtenFiles.first().parent}")
            }
        }
        group.createNotification("JPA 3D Export 완료", msg, NotificationType.INFORMATION)
            .notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "JPA 3D Export"
    }
}
