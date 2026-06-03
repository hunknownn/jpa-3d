package com.jpa3d.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import com.jpa3d.export.DdlDialect

/**
 * 플러그인 전역(애플리케이션) 설정의 영속 상태.
 *
 * **설정 → 도구 → JPA 3D** ([Jpa3dConfigurable]) 에서 편집되며 다음 동작에 반영된다:
 *  - 뷰어 기본값  : 툴윈도우를 열 때 초기 파라미터 ([com.jpa3d.BridgeInjector] → `window.__JPA3D_DEFAULTS__`)
 *  - 분석 패키지 필터: [com.jpa3d.Jpa3dRequestHandler] 의 그래프 필터 단계
 *  - Export 기본값  : [com.jpa3d.export.ExportDialog] 초기값
 *
 * 애플리케이션 레벨이라 IDE 전역에 하나만 존재한다(프로젝트마다 다르지 않음).
 */
@Service(Service.Level.APP)
@State(name = "Jpa3dSettings", storages = [Storage("jpa3d.xml")])
class Jpa3dSettings : PersistentStateComponent<Jpa3dSettings.State> {

    /** 직렬화 대상 — XmlSerializer 규칙상 모든 필드는 `var` + 기본값. */
    class State {
        // === 뷰어 기본값 ===
        /** 기본 렌더 뷰: "3d" | "2d". */
        var defaultView: String = "3d"
        /** 기본 범위: "all" | "seed". */
        var defaultScope: String = "all"
        /** 중심(seed) 모드에서 seed 로부터의 기본 홉 깊이. */
        var defaultDepth: Int = 2
        var showColumns: Boolean = false
        var showRepository: Boolean = false
        var showExtends: Boolean = true

        // === 분석 대상 패키지 필터 (줄바꿈/쉼표 구분, 접두 매칭). 비면 전체 허용 ===
        var includePackages: String = ""
        var excludePackages: String = ""

        // === Export 기본값 ===
        var ddlDialect: DdlDialect = DdlDialect.POSTGRES
        var ddlSnakeCase: Boolean = true
        var ddlDropExisting: Boolean = false
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /**
     * 뷰어가 초기 파라미터로 쓰는 기본값 JSON.
     * [com.jpa3d.BridgeInjector](페이지 로드 시)와 [com.jpa3d.Jpa3dToolWindowFactory](재오픈 시 push)가
     * 같은 형태를 주입하도록 한 곳에 모은다. 값은 모두 plugin 이 통제하는 안전한 리터럴이라 escape 불필요.
     */
    fun viewerDefaultsJson(): String =
        """{"view":"${myState.defaultView}","scope":"${myState.defaultScope}",""" +
            """"depth":${myState.defaultDepth},"col":${myState.showColumns},""" +
            """"repo":${myState.showRepository},"extends":${myState.showExtends}}"""

    companion object {
        fun getInstance(): Jpa3dSettings = service()
    }
}
