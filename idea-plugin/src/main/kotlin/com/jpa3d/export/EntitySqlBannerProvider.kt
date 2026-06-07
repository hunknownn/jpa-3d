package com.jpa3d.export

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.JBUI
import com.jpa3d.Jpa3dBundle
import com.jpa3d.Jpa3dIcons
import com.jpa3d.ViewerSeeder
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
 *  - 눈(보기) 아이콘 → [ViewerSeeder] 로 현재 엔티티를 뷰어 중심(시드)으로 추가.
 * 우측 톱니 → 설정(설정 → 도구 → JPA 3D)을 열어 거터/배너 노출 위치를 바꾼다.
 *
 * 노출 여부는 [Jpa3dSettings.EntitySqlPlacement] 를 따른다(거터 "SQL" 글자 마커
 * [EntitySqlLineMarkerProvider] 와 한 설정으로 묶임). 인덱싱 중에는 뜨지 않고 완료 후 자동 갱신.
 *
 * 클릭 가능한 아이콘은 빈 텍스트 action label 로는 히트 영역이 안 잡혀(이전 버그) 동작하지 않았다.
 * 그래서 마우스 리스너를 단 JLabel 을 직접 배치한다 — 왼쪽 줄은 BorderLayout.WEST, 톱니는 myLinksPanel.
 *
 * 배경은 알림용 컬러(Status.Info)가 아니라 에디터 본문 배경색을 따른다 — 다크/라이트 어느 테마에서도
 * 코드 영역과 자연스럽게 이어지는 툴바처럼 보이게 하기 위함.
 */
class EntitySqlBannerProvider : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (!Jpa3dSettings.getInstance().state.entitySqlPlacement.banner) return null
        val found = EntityClassLocator.find(project, file) ?: return null
        return Function { fileEditor ->
            // 알림용 컬러 배경(Status.Info) 대신 에디터 본문과 같은 배경색을 써서 IDE 테마와 자연스럽게 섞이게 한다.
            val editorBg = (fileEditor as? TextEditor)?.editor?.colorsScheme?.defaultBackground
            object : EditorNotificationPanel(fileEditor, editorBg) {
                init {
                    text = "" // 메인 라벨 비움 — 왼쪽 아이콘 줄을 직접 올린다.
                    // 기본 배너는 세로 패딩이 커서 키 큰 띠가 된다. 패딩을 줄여 20px 아이콘이 높이를
                    // 정하는 컴팩트한 툴바형으로 하되, 아이콘이 상/하 경계선에 붙지 않도록 위아래 3px 여유.
                    border = JBUI.Borders.empty(3, 8)
                    add(leftActions(project, found), BorderLayout.WEST)
                    // 설정(톱니)은 관례대로 우측 끝.
                    myLinksPanel.add(clickableIcon(Jpa3dIcons.Gear, Jpa3dBundle.message("editor.sql.settings")) {
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
            add(clickableIcon(Jpa3dIcons.Seed, Jpa3dBundle.message("editor.seed.tooltip", found.simpleName)) {
                ViewerSeeder.addEntity(project, found.fqn)
            })
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
