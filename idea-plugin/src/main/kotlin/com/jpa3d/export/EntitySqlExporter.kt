package com.jpa3d.export

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.jpa3d.Jpa3dBundle
import com.jpa3d.analyzer.Jpa3dAnalysisCache
import com.jpa3d.settings.Jpa3dSettings

/**
 * 단일 엔티티의 DDL(SQL)을 생성해 읽기전용 에디터 탭으로 연다.
 *
 * 에디터 상단 배너([EntitySqlBannerProvider]) 버튼이 호출한다. 흐름:
 *  1. 백그라운드에서 분석 캐시 → 그래프 → [ExportConverter.toSingleEntityModel] (이 엔티티 + 상속 부모).
 *  2. 저장된 Export 기본값(설정 → 도구 → JPA 3D)의 방언/snake_case/DROP 옵션으로 [DdlExporter] 렌더.
 *  3. EDT 에서 LightVirtualFile(`<Entity>.<dialect>.sql`, 읽기전용)을 열어 바로 복사/검토.
 *
 * 다이얼로그를 거치지 않는 빠른 경로 — 방언 등을 바꾸려면 기존 Tools → JPA 3D: Export… 다이얼로그를 쓴다.
 */
object EntitySqlExporter {

    private val LOG = logger<EntitySqlExporter>()

    fun export(project: Project, fqn: String, simpleName: String) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, Jpa3dBundle.message("entitySql.progress.text", simpleName), true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val graph = project.service<Jpa3dAnalysisCache>().getGraphData()
                    if (graph.indexing) {
                        notify(project, NotificationType.WARNING, Jpa3dBundle.message("entitySql.notify.indexing"))
                        return
                    }
                    val model = ExportConverter.toSingleEntityModel(graph, fqn)
                    if (model.entities.isEmpty()) {
                        notify(project, NotificationType.WARNING, Jpa3dBundle.message("entitySql.notify.notFound", simpleName))
                        return
                    }
                    val s = Jpa3dSettings.getInstance().state
                    val ddl = DdlExporter(s.ddlDialect, s.ddlSnakeCase, s.ddlDropExisting).render(model)
                    openInEditor(project, simpleName, s.ddlDialect, ddl)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("JPA 3D entity SQL export failed", e)
                    notify(project, NotificationType.ERROR, e.message ?: e.javaClass.simpleName)
                }
            }
        })
    }

    /** 생성한 DDL 을 읽기전용 인메모리 파일로 에디터에 띄운다 (EDT). */
    private fun openInEditor(project: Project, simpleName: String, dialect: DdlDialect, content: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val fileName = "$simpleName.${dialect.fileSuffix}.sql"
            val file = LightVirtualFile(fileName, sqlFileType(), content).apply { isWritable = false }
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    /** .sql 이 등록돼 있으면 그 타입(SQL 하이라이팅), 아니면 PlainText — Community 처럼 SQL 미지원 IDE 대비. */
    private fun sqlFileType() = FileTypeManager.getInstance().getFileTypeByExtension("sql")
        .takeUnless { it is UnknownFileType } ?: PlainTextFileType.INSTANCE

    private fun notify(project: Project, type: NotificationType, body: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ExportAction.NOTIFICATION_GROUP)
            .createNotification(Jpa3dBundle.message("entitySql.notify.title"), body, type)
            .notify(project)
    }
}
