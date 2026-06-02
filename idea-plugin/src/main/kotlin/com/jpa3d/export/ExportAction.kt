package com.jpa3d.export

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
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
                indicator.text = "JPA 3D 모델 변환 중…"
                try {
                    val result = ExportRunner(project).run(options)
                    notify(project, result)
                } catch (e: ProcessCanceledException) {
                    throw e // 사용자 취소 — 정상 흐름
                } catch (e: Exception) {
                    LOG.warn("JPA 3D export failed", e)
                    notifyError(project, e)
                }
            }
        })
    }

    private fun notify(project: Project, result: ExportRunner.Result) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        val outputDir = result.writtenFiles.firstOrNull()?.parent

        // 하나도 못 만들었거나 일부 스킵됐으면 경고로 — 사용자가 결과를 오해하지 않게.
        val type = when {
            result.writtenFiles.isEmpty() -> NotificationType.WARNING
            result.skippedFormats.isNotEmpty() -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }
        val title = if (result.writtenFiles.isEmpty()) "JPA 3D Export — 생성된 파일 없음" else "JPA 3D Export 완료"

        val body = buildString {
            if (result.writtenFiles.isNotEmpty()) {
                append("파일 ${result.writtenFiles.size}개 생성\n")
                append(result.writtenFiles.joinToString("\n") { "• ${it.fileName}" })
            }
            if (result.skippedFormats.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("스킵됨:\n")
                append(result.skippedFormats.joinToString("\n") { "• $it" })
            }
            if (isEmpty()) append("선택한 항목이 모두 스킵되었습니다.")
        }

        val notification = group.createNotification(title, body, type)
        if (outputDir != null && RevealFileAction.isSupported()) {
            notification.addAction(NotificationAction.createSimple("폴더 열기") {
                RevealFileAction.openDirectory(outputDir.toFile())
            })
        }
        notification.notify(project)
    }

    private fun notifyError(project: Project, e: Exception) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        group.createNotification(
            "JPA 3D Export 실패",
            e.message ?: e.javaClass.simpleName,
            NotificationType.ERROR
        ).notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "JPA 3D Export"
        private val LOG = logger<ExportAction>()
    }
}
