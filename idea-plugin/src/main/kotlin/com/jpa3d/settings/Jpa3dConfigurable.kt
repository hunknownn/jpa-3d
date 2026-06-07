package com.jpa3d.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
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
 * 설정 → 도구 → JPA 3D 페이지.
 *
 * 단일 페이지를 세 그룹(뷰어 기본값 / 분석 대상 / Export 기본값)으로 나누고,
 * 각 컨트롤을 [Jpa3dSettings] 의 영속 상태에 바인딩한다. 사용자가 "적용/확인" 을 누르면
 * BoundConfigurable 이 바인딩된 프로퍼티에 값을 써넣는다(별도 apply 구현 불필요).
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

        group(Jpa3dBundle.message("settings.viewer.group")) {
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

        group(Jpa3dBundle.message("settings.filter.group")) {
            row { comment(Jpa3dBundle.message("settings.filter.comment")) }
            row(Jpa3dBundle.message("settings.filter.include")) {
                textArea()
                    .align(AlignX.FILL)
                    .applyToComponent { rows = 3 }
                    .bindText(state::includePackages)
            }
            row(Jpa3dBundle.message("settings.filter.exclude")) {
                textArea()
                    .align(AlignX.FILL)
                    .applyToComponent { rows = 3 }
                    .bindText(state::excludePackages)
            }
        }

        group(Jpa3dBundle.message("settings.export.group")) {
            row(Jpa3dBundle.message("settings.export.dialect")) {
                comboBox(
                    DdlDialect.values().toList(),
                    // textListCellRenderer 는 243~262 전 버전에 존재하는 비-deprecated DSL.
                    // (SimpleListCellRenderer.create 는 262 에서 forRemoval 로 표시됨.)
                    // comboBox 의 renderer 파라미터가 ListCellRenderer<in T?> 라 람다 파라미터를
                    // nullable 로 받아 T 를 DdlDialect? 로 추론시킨다.
                    textListCellRenderer { value: DdlDialect? -> value?.displayName ?: "" }
                ).bindItem({ state.ddlDialect }, { state.ddlDialect = it ?: DdlDialect.POSTGRES })
            }
            row {
                checkBox(Jpa3dBundle.message("settings.export.snakeCase"))
                    .bindSelected(state::ddlSnakeCase)
            }
            row {
                checkBox(Jpa3dBundle.message("settings.export.dropExisting"))
                    .bindSelected(state::ddlDropExisting)
            }
        }
    }

    /** 언어 콤보 항목 라벨 — 현재 표시 언어 기준. */
    private fun uiLangLabel(lang: Jpa3dSettings.UiLang?): String = when (lang) {
        Jpa3dSettings.UiLang.KO -> Jpa3dBundle.message("settings.language.ko")
        Jpa3dSettings.UiLang.EN -> Jpa3dBundle.message("settings.language.en")
        else -> Jpa3dBundle.message("settings.language.auto")
    }
}
