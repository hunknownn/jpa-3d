package com.jpa3d.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.EditorNotifications
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.jpa3d.Jpa3dBundle
import com.jpa3d.export.DdlDialect

/**
 * JPA 3D 설정의 하위 탭들. 모두 같은 [Jpa3dSettings] 상태에 바인딩되며, 부모
 * [Jpa3dConfigurable.getConfigurables] 가 직접 제공한다(Composite).
 *
 * 탭 이름은 정적 displayName 대신 [Jpa3dBundle](플러그인 언어 설정) 을 따르므로
 * EP 등록 대신 Composite 로 매단다([Jpa3dConfigurable] 주석 참고). deep-link/검색 안정성을 위해
 * 각 탭은 [SearchableConfigurable] 로 옛 plugin.xml id 를 [getId] 에 그대로 보존한다.
 */

/** 하위 탭 — 뷰어 기본값. 탭 이름은 정적 displayName 대신 언어 설정을 따르도록 번들에서. */
class Jpa3dViewerConfigurable : BoundConfigurable(Jpa3dBundle.message("settings.tab.viewer")), SearchableConfigurable {

    override fun getId(): String = "com.jpa3d.settings.viewer"

    private val state get() = Jpa3dSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row(Jpa3dBundle.message("settings.viewer.view")) {
            comboBox(listOf("3d", "2d"))
                .bindItem({ state.defaultView }, { state.defaultView = it ?: "3d" })
        }
        row(Jpa3dBundle.message("settings.viewer.scope")) {
            comboBox(listOf("all", "seed"))
                .bindItem({ state.defaultScope }, { state.defaultScope = it ?: "all" })
        }
        row { comment(Jpa3dBundle.message("settings.viewer.scope.comment")) }
        row(Jpa3dBundle.message("settings.viewer.depth")) {
            spinner(0..50, 1).bindIntValue(state::defaultDepth)
        }
        row { comment(Jpa3dBundle.message("settings.viewer.depth.comment")) }
        row {
            checkBox(Jpa3dBundle.message("settings.viewer.showColumns")).bindSelected(state::showColumns)
            checkBox(Jpa3dBundle.message("settings.viewer.showRepository")).bindSelected(state::showRepository)
            checkBox(Jpa3dBundle.message("settings.viewer.showExtends")).bindSelected(state::showExtends)
        }
    }
}

/** 하위 탭 — 분석 대상 패키지 필터. */
class Jpa3dAnalysisConfigurable : BoundConfigurable(Jpa3dBundle.message("settings.tab.analysis")), SearchableConfigurable {

    override fun getId(): String = "com.jpa3d.settings.analysis"

    private val state get() = Jpa3dSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row { comment(Jpa3dBundle.message("settings.filter.comment")) }
        row(Jpa3dBundle.message("settings.filter.include")) {
            textArea().align(AlignX.FILL).applyToComponent { rows = 3 }.bindText(state::includePackages)
        }
        row(Jpa3dBundle.message("settings.filter.exclude")) {
            textArea().align(AlignX.FILL).applyToComponent { rows = 3 }.bindText(state::excludePackages)
        }
    }
}

/** 하위 탭 — Export(DDL) 기본값. */
class Jpa3dExportConfigurable : BoundConfigurable(Jpa3dBundle.message("settings.tab.export")), SearchableConfigurable {

    override fun getId(): String = "com.jpa3d.settings.export"

    private val state get() = Jpa3dSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row(Jpa3dBundle.message("settings.export.dialect")) {
            comboBox(
                DdlDialect.values().toList(),
                textListCellRenderer { value: DdlDialect? -> value?.displayName ?: "" }
            ).bindItem({ state.ddlDialect }, { state.ddlDialect = it ?: DdlDialect.POSTGRES })
        }
        row { checkBox(Jpa3dBundle.message("settings.export.snakeCase")).bindSelected(state::ddlSnakeCase) }
        row { checkBox(Jpa3dBundle.message("settings.export.dropExisting")).bindSelected(state::ddlDropExisting) }
    }
}

/**
 * 하위 탭 — 에디터 진입점(거터/배너) 설정. 배너 우측 톱니가 이 탭을 연다.
 * 추후 "표시 항목 × 위치" 표(SQL 추출 / seed 추가 …)로 확장될 자리.
 */
class Jpa3dEditorConfigurable : BoundConfigurable(Jpa3dBundle.message("settings.tab.editor")), SearchableConfigurable {

    override fun getId(): String = "com.jpa3d.settings.editor"

    private val state get() = Jpa3dSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row(Jpa3dBundle.message("settings.entitySql.placement")) {
            comboBox(
                Jpa3dSettings.EntitySqlPlacement.values().toList(),
                textListCellRenderer { value: Jpa3dSettings.EntitySqlPlacement? -> placementLabel(value) }
            ).bindItem({ state.entitySqlPlacement }, {
                state.entitySqlPlacement = it ?: Jpa3dSettings.EntitySqlPlacement.BOTH
            })
        }
        row { comment(Jpa3dBundle.message("settings.entitySql.comment")) }
    }

    /** 적용 시 열린 에디터의 배너/거터 마커를 즉시 재평가 — 재오픈 없이 반영. */
    override fun apply() {
        super.apply()
        for (project in ProjectManager.getInstance().openProjects) {
            EditorNotifications.getInstance(project).updateAllNotifications()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    private fun placementLabel(p: Jpa3dSettings.EntitySqlPlacement?): String = when (p) {
        Jpa3dSettings.EntitySqlPlacement.GUTTER -> Jpa3dBundle.message("settings.entitySql.gutter")
        Jpa3dSettings.EntitySqlPlacement.BANNER -> Jpa3dBundle.message("settings.entitySql.banner")
        Jpa3dSettings.EntitySqlPlacement.OFF -> Jpa3dBundle.message("settings.entitySql.off")
        else -> Jpa3dBundle.message("settings.entitySql.both")
    }
}
