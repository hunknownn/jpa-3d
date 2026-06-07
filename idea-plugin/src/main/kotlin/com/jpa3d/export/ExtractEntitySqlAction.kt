package com.jpa3d.export

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.jpa3d.Jpa3dBundle

/**
 * 에디터 우클릭(또는 사용자 지정 단축키)으로 현재 `@Entity` 의 SQL 을 추출하는 액션.
 *
 * 거터/배너 아이콘과 동일하게 [EntitySqlExporter] 를 호출한다. 아이콘 노출 설정
 * ([Jpa3dSettings.EntitySqlPlacement]) 과 무관하게 항상 제공되는 메뉴/키보드 경로 —
 * 기본 단축키는 충돌 방지를 위해 두지 않고, 사용자가 Keymap 에서 직접 할당한다.
 */
class ExtractEntitySqlAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = Jpa3dBundle.message("editor.sql.action.text")
        e.presentation.isEnabledAndVisible = resolve(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val found = resolve(e) ?: return
        EntitySqlExporter.export(project, found.fqn, found.simpleName)
    }

    private fun resolve(e: AnActionEvent): EntityClassLocator.Found? {
        val project = e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return EntityClassLocator.find(project, file)
    }
}
