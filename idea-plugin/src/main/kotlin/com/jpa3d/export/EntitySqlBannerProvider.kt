package com.jpa3d.export

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.JBUI
import com.jpa3d.Jpa3dBundle
import com.jpa3d.Jpa3dIcons
import com.jpa3d.settings.Jpa3dEditorConfigurable
import com.jpa3d.settings.Jpa3dSettings
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Function
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 열린 파일이 JPA `@Entity` 면 에디터 상단 배너 **왼쪽**에 액션 아이콘 줄을, **오른쪽**에 설정(톱니)을 둔다.
 *
 * 현재 액션:
 *  - SQL 아이콘 → [EntitySqlExporter] 로 현재 엔티티 DDL 을 읽기전용 탭에 띄움.
 *  - (예정) 뷰어 seed 추가 등 — 왼쪽 아이콘 줄([leftActions])에 항목만 더하면 됨.
 * 우측 톱니 → 설정(설정 → 도구 → JPA 3D)을 열어 거터/배너 노출 위치를 바꾼다.
 *
 * 노출 여부는 [Jpa3dSettings.EntitySqlPlacement] 를 따른다(거터 "SQL" 글자 마커
 * [EntitySqlLineMarkerProvider] 와 한 설정으로 묶임). 인덱싱 중에는 뜨지 않고 완료 후 자동 갱신.
 *
 * 클릭 가능한 아이콘은 빈 텍스트 action label 로는 히트 영역이 안 잡혀(이전 버그) 동작하지 않았다.
 * 그래서 마우스 리스너를 단 JLabel 을 직접 배치한다 — 왼쪽 줄은 BorderLayout.WEST, 톱니는 myLinksPanel.
 */
class EntitySqlBannerProvider : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (!Jpa3dSettings.getInstance().state.entitySqlPlacement.banner) return null
        val found = EntityClassLocator.find(project, file) ?: return null
        return Function { fileEditor ->
            object : EditorNotificationPanel(fileEditor, Status.Info) {
                init {
                    text = "" // 메인 라벨 비움 — 왼쪽 아이콘 줄을 직접 올린다.
                    add(leftActions(project, found), BorderLayout.WEST)
                    // 설정(톱니)은 관례대로 우측 끝.
                    myLinksPanel.add(clickableIcon(AllIcons.General.GearPlain, Jpa3dBundle.message("editor.sql.settings")) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, Jpa3dEditorConfigurable::class.java)
                    })
                }
            }
        }
    }

    /** 배너 왼쪽의 액션 아이콘 줄. 추후 기능은 여기에 아이콘만 추가하면 된다. */
    private fun leftActions(project: Project, found: EntityClassLocator.Found): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(4)
            add(clickableIcon(Jpa3dIcons.Sql, Jpa3dBundle.message("editor.sql.tooltip", found.simpleName)) {
                EntitySqlExporter.export(project, found.fqn, found.simpleName)
            })
            // 예정: add(clickableIcon(seedIcon, seedTooltip) { 뷰어에 seed 로 추가 })
        }

    /** 아이콘 + 핸드 커서 + 툴팁 + 클릭 핸들러를 가진 라벨. */
    private fun clickableIcon(icon: Icon, tip: String, onClick: () -> Unit): JLabel = JLabel(icon).apply {
        toolTipText = tip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(0, 2)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
    }
}
