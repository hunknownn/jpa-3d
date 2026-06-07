package com.jpa3d.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.jpa3d.Jpa3dBundle

/**
 * 설정 → 도구 → JPA 3D — **부모 노드**.
 *
 * 하위 탭(Viewer / Analysis / Export / Editor)은 각각 별도 [BoundConfigurable] 로 분리돼
 * 이 노드의 [getConfigurables] 로 직접 제공된다(JPA Buddy 와 같은 구조). 부모 페이지 자체는
 * 표시 언어만 담당한다.
 *
 * 자식을 plugin.xml 의 `<applicationConfigurable parentId=...>` EP 로 등록하지 않고
 * [Configurable.Composite] 로 매다는 이유: EP 등록은 정적 `displayName`/`key` 를 요구하는데,
 * 자식 탭 이름은 [Jpa3dBundle](플러그인 자체 언어 설정) 을 따라야 해서 정적 문자열로 둘 수 없다.
 * EP 의 `displayName` 을 비우면 플랫폼이 *이름만 얻으려고* 자식 클래스를 강제 로딩하며
 * `PluginException("No display name specified ...")` 을 던진다(설정 트리 정렬 시점).
 * Composite 로 직접 제공하면 그 요구가 적용되지 않아 각 자식의 동적 `getDisplayName()` 이 그대로 쓰인다.
 * deep-link/검색 ID 는 각 자식이 [com.intellij.openapi.options.SearchableConfigurable.getId] 로 보존한다.
 */
class Jpa3dConfigurable : BoundConfigurable("JPA 3D"), Configurable.Composite {

    private val state get() = Jpa3dSettings.getInstance().state

    /** 표시 순서 = plugin.xml 시절과 동일(Viewer → Analysis → Export → Editor). */
    override fun getConfigurables(): Array<Configurable> = arrayOf(
        Jpa3dViewerConfigurable(),
        Jpa3dAnalysisConfigurable(),
        Jpa3dExportConfigurable(),
        Jpa3dEditorConfigurable(),
    )

    override fun createPanel(): DialogPanel = panel {
        group(Jpa3dBundle.message("settings.language.group")) {
            row(Jpa3dBundle.message("settings.language.label")) {
                comboBox(
                    Jpa3dSettings.UiLang.values().toList(),
                    textListCellRenderer { value: Jpa3dSettings.UiLang? -> uiLangLabel(value) }
                ).bindItem({ state.uiLang }, { state.uiLang = it ?: Jpa3dSettings.UiLang.AUTO })
            }
            row { comment(Jpa3dBundle.message("settings.language.comment")) }
        }
    }

    /** 언어 콤보 항목 라벨 — 현재 표시 언어 기준. */
    private fun uiLangLabel(lang: Jpa3dSettings.UiLang?): String = when (lang) {
        Jpa3dSettings.UiLang.KO -> Jpa3dBundle.message("settings.language.ko")
        Jpa3dSettings.UiLang.EN -> Jpa3dBundle.message("settings.language.en")
        else -> Jpa3dBundle.message("settings.language.auto")
    }
}
