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
        group("뷰어 기본값") {
            row("기본 뷰:") {
                comboBox(listOf("3d", "2d"))
                    .bindItem({ state.defaultView }, { state.defaultView = it ?: "3d" })
            }
            row("기본 범위:") {
                comboBox(listOf("all", "seed"))
                    .bindItem({ state.defaultScope }, { state.defaultScope = it ?: "all" })
            }
            row { comment("seed = 중심 모드(상단 검색으로 중심 엔티티/패키지 선택)") }
            row("기본 깊이:") {
                spinner(0..50, 1).bindIntValue(state::defaultDepth)
            }
            row { comment("중심 모드에서 seed 로부터의 홉 수") }
            row {
                checkBox("컬럼 표시").bindSelected(state::showColumns)
                checkBox("리포지토리 표시").bindSelected(state::showRepository)
                checkBox("상속 표시").bindSelected(state::showExtends)
            }
        }

        group("분석 대상 패키지 필터") {
            row { comment("한 줄에 패키지 하나. 접두 매칭(com.foo → com.foo 및 하위 패키지). 비우면 전체.") }
            row("포함(include):") {
                textArea()
                    .align(AlignX.FILL)
                    .applyToComponent { rows = 3 }
                    .bindText(state::includePackages)
            }
            row("제외(exclude):") {
                textArea()
                    .align(AlignX.FILL)
                    .applyToComponent { rows = 3 }
                    .bindText(state::excludePackages)
            }
        }

        group("Export 기본값") {
            row("DDL 방언:") {
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
                checkBox("snake_case 이름 변환 (UserAccount → user_account)")
                    .bindSelected(state::ddlSnakeCase)
            }
            row {
                checkBox("DROP TABLE IF EXISTS 헤더 추가 (재실행 안전)")
                    .bindSelected(state::ddlDropExisting)
            }
        }
    }
}
