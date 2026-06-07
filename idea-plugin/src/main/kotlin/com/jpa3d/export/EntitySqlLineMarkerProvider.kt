package com.jpa3d.export

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.jpa3d.Jpa3dBundle
import com.jpa3d.analyzer.JpaAnnotations
import com.jpa3d.settings.Jpa3dSettings
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElementOfType

/**
 * JPA `@Entity` 클래스 선언 줄 옆 거터(gutter)에 SQL 추출 아이콘을 단다.
 *
 * 클릭하면 [EntitySqlExporter] 가 현재 엔티티 하나의 DDL 을 만들어 읽기전용 에디터 탭에 띄운다 —
 * 상단 배너([EntitySqlBannerProvider])와 동일 동작의 "아이콘" 진입점. 두 표현 중 어느 쪽을 남길지
 * 비교하기 위해 동시에 제공한다.
 *
 * Java/Kotlin 공통 — UAST 로 클래스를 해석하므로 plugin.xml 에 두 언어로 등록한다.
 */
class EntitySqlLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!Jpa3dSettings.getInstance().state.entitySqlPlacement.gutter) return null
        // 클래스 이름 식별자(리프)에만 마커를 단다 — 플랫폼의 "leaf only" 계약 + 줄당 1개 보장.
        val uClass = element.parent?.toUElementOfType<UClass>() ?: return null
        if (uClass.uastAnchor?.sourcePsi != element) return null
        if (uClass.uAnnotations.none { it.qualifiedName in JpaAnnotations.ENTITY }) return null

        val fqn = uClass.qualifiedName ?: return null
        val name = fqn.substringAfterLast('.')
        val project = element.project
        val tooltip = Jpa3dBundle.message("editor.sql.tooltip", name)

        return LineMarkerInfo(
            element,
            element.textRange,
            SqlTextIcon,
            { _ -> tooltip },
            { _, _ -> EntitySqlExporter.export(project, fqn, name) },
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }
}
