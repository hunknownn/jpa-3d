package com.jpa3d.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.jpa3d.Jpa3dBundle

/**
 * 설정 → 도구 → JPA 3D — **부모 노드**.
 *
 * 하위 탭(Viewer / Analysis / Export / Editor)은 각각 별도 [BoundConfigurable] 로 분리돼
 * plugin.xml 에서 `parentId="com.jpa3d.settings"` 로 이 노드 아래에 매달린다(JPA Buddy 와 같은 구조).
 * 부모 페이지 자체는 표시 언어만 담당한다.
 */
class Jpa3dConfigurable : BoundConfigurable("JPA 3D") {

    private val state get() = Jpa3dSettings.getInstance().state

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
