package com.jpa3d.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.DynamicBundle
import com.intellij.util.xmlb.XmlSerializerUtil
import com.jpa3d.export.DdlDialect
import java.util.Locale

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

    /**
     * 표시 언어 — 플러그인 UI(다이얼로그/알림/설정)와 뷰어(JCEF)가 함께 따른다.
     *
     * [AUTO] 는 IDE 로케일([DynamicBundle.getLocale])을 그대로 따라가고,
     * 나머지는 IDE 설정과 무관하게 강제로 override 한다. 즉:
     *  - AUTO + IDE(ko) → ko, AUTO + IDE(en) → en
     *  - KO  + IDE(en) → ko, EN + IDE(ko) → en
     */
    enum class UiLang(val locale: Locale?) {
        /** IDE 로케일을 따름. */
        AUTO(null),
        KO(Locale.KOREAN),
        EN(Locale.ENGLISH);
    }

    /**
     * `@Entity` 파일에서 "SQL 추출" 진입점을 어디에 노출할지.
     *  - [BOTH]   : 거터 "SQL" 글자 + 상단 배너 아이콘 (기본)
     *  - [GUTTER] : 왼쪽 거터 글자만 (상단 밴드를 건드리지 않음)
     *  - [BANNER] : 상단 배너 아이콘만
     *  - [OFF]    : 둘 다 끔
     */
    enum class EntitySqlPlacement {
        BOTH, GUTTER, BANNER, OFF;

        val gutter: Boolean get() = this == BOTH || this == GUTTER
        val banner: Boolean get() = this == BOTH || this == BANNER
    }

    /** 직렬화 대상 — XmlSerializer 규칙상 모든 필드는 `var` + 기본값. */
    class State {
        // === 표시 언어 ===
        /** UI 표시 언어. 기본 AUTO = IDE 언어를 따름. */
        var uiLang: UiLang = UiLang.AUTO

        // === 뷰어 기본값 ===
        /** 기본 렌더 뷰: "3d" | "2d". */
        var defaultView: String = "3d"
        /** 기본 범위: "all" | "seed". */
        var defaultScope: String = "all"
        /** 중심(seed) 모드에서 seed 로부터의 기본 홉 깊이. */
        var defaultDepth: Int = 2
        var showColumns: Boolean = true
        var showRepository: Boolean = false
        var showExtends: Boolean = true

        // === 분석 대상 패키지 필터 (줄바꿈/쉼표 구분, 접두 매칭). 비면 전체 허용 ===
        var includePackages: String = ""
        var excludePackages: String = ""

        // === Export 기본값 ===
        var ddlDialect: DdlDialect = DdlDialect.POSTGRES
        var ddlSnakeCase: Boolean = true
        var ddlDropExisting: Boolean = false

        /** `@Entity` 에디터의 "SQL 추출" 아이콘 노출 위치 (거터/배너/둘다/끔). */
        var entitySqlPlacement: EntitySqlPlacement = EntitySqlPlacement.BOTH
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
    /**
     * 실제 적용 로케일 — override 모델. 설정이 AUTO 면 IDE 로케일, 아니면 강제 값.
     * 플러그인 번들([com.jpa3d.Jpa3dBundle])과 뷰어 로케일 주입([com.jpa3d.BridgeInjector]) 모두 여기를 본다.
     */
    fun effectiveLocale(): Locale = myState.uiLang.locale ?: DynamicBundle.getLocale()

    /** 뷰어로 내려보낼 언어 코드 ("ko" / "en" …). */
    fun effectiveLanguageTag(): String = effectiveLocale().language

    fun viewerDefaultsJson(): String =
        """{"view":"${myState.defaultView}","scope":"${myState.defaultScope}",""" +
            """"depth":${myState.defaultDepth},"col":${myState.showColumns},""" +
            """"repo":${myState.showRepository},"extends":${myState.showExtends}}"""

    companion object {
        fun getInstance(): Jpa3dSettings = service()
    }
}
