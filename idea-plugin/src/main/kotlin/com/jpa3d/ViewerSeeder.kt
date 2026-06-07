package com.jpa3d

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.jpa3d.analyzer.Jpa3dAnalysisCache
import com.jpa3d.export.ExportAction

/**
 * JPA 3D 뷰어에 **중심(시드)** 을 추가하는 공통 진입점.
 *
 * 패키지 우클릭([Jpa3dAddPackageAction])과 엔티티 에디터 배너([com.jpa3d.export.EntitySqlBannerProvider])가
 * 같은 흐름을 공유한다:
 *  1. JPA 3D 툴윈도우를 activate — 콘텐츠(뷰어 패널) 생성 후 EDT 콜백에서 push.
 *  2. 살아있는 뷰어에 `jpa3d:add-seed` 이벤트를 dispatch. 툴윈도우를 새로 여는 경우
 *     React mount 전에 dispatch 될 수 있어, 브라우저 측 `__JPA3D_ADD_SEED_READY__` 플래그가
 *     설 때까지 폴링 후 dispatch 한다.
 *
 * 뷰어는 시드를 받으면 자동으로 center(seed) 범위로 전환한다(ErdApp.addSeed).
 *
 * 단일 엔티티([addEntity])는 보내기 전에 분석된 모델에 실제로 있는지 검사한다 — 인덱싱 중이거나
 * 패키지 필터로 제외돼 그래프에 없으면 뷰어를 열어도 아무것도 안 보여 "눌렀는데 무반응"이 되므로,
 * SQL 추출([com.jpa3d.export.EntitySqlExporter])과 같은 톤의 알림으로 이유를 알린다.
 */
object ViewerSeeder {

    private const val TOOL_WINDOW_ID = "JPA 3D"
    private val log = logger<ViewerSeeder>()

    /**
     * 단일 엔티티를 FQN 시드로 추가. 백그라운드에서 모델 존재를 검사한 뒤 EDT 에서 뷰어에 push.
     * @param simpleName 알림 메시지에 쓰는 표시용 이름.
     */
    fun addEntity(project: Project, fqn: String, simpleName: String) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, Jpa3dBundle.message("seed.progress.text", simpleName), true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val graph = project.service<Jpa3dAnalysisCache>().getGraphData()
                if (graph.indexing) {
                    notify(project, NotificationType.WARNING, Jpa3dBundle.message("entitySql.notify.indexing"))
                    return
                }
                if (graph.nodes.none { it.id == fqn }) {
                    notify(project, NotificationType.WARNING, Jpa3dBundle.message("seed.notify.notFound", simpleName))
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) seedOnEdt(project, fqn, "fqn")
                }
            }
        })
    }

    /** 패키지(+하위 전체)를 시드로 추가. 호출 측이 EDT(액션 핸들러)임을 가정. */
    fun addPackage(project: Project, pkg: String) = seedOnEdt(project, pkg, "package")

    /** 툴윈도우를 activate 하고 시드를 push (EDT). 툴윈도우가 없으면 알림으로 알린다. */
    private fun seedOnEdt(project: Project, value: String, type: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: run {
            log.warn("JPA 3D tool window not found")
            notify(project, NotificationType.WARNING, Jpa3dBundle.message("seed.notify.noToolWindow"))
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
        val js = """
            (function() {
              var detail = ${seedDetailJson(value, type)};
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

    private fun notify(project: Project, type: NotificationType, body: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ExportAction.NOTIFICATION_GROUP)
            .createNotification(Jpa3dBundle.message("seed.notify.title"), body, type)
            .notify(project)
    }
}

/**
 * `jpa3d:add-seed` 이벤트의 detail JSON 리터럴을 만든다. 뷰어 SeedRef 형태 `{value, type}`.
 * 순수 함수 — 따옴표/역슬래시가 든 값이 JS 문자열을 깨거나 주입되지 않도록 이스케이프를 단위 테스트한다.
 */
internal fun seedDetailJson(value: String, type: String): String =
    """{"value":"${jsEscapeForSeed(value)}","type":"${jsEscapeForSeed(type)}"}"""

/** 자바스크립트 문자열 리터럴 안전 이스케이프 (역슬래시 먼저, 그다음 큰따옴표). */
internal fun jsEscapeForSeed(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
