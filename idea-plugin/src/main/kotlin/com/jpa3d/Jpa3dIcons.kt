package com.jpa3d

import com.intellij.openapi.util.IconLoader

/**
 * 플러그인 번들 아이콘. 라이트/다크 변형은 IconLoader 가 테마에 맞춰 `*_dark.svg` 를 자동 선택한다.
 */
object Jpa3dIcons {
    /** DB 실린더 모양 SQL 아이콘 — 엔티티 SQL 추출 진입점(거터/배너)에서 사용. */
    @JvmField
    val Sql = IconLoader.getIcon("/icons/sql.svg", Jpa3dIcons::class.java)

    /** DNA 이중나선 + 좌측 하단 '+' 배지 — 현재 엔티티를 뷰어 중심(시드)으로 추가하는 진입점에서 사용. */
    @JvmField
    val Seed = IconLoader.getIcon("/icons/seed.svg", Jpa3dIcons::class.java)

    /** 톱니(설정) 아이콘 — 플랫폼 기본 회색 대신 SQL/눈 아이콘과 같은 딥 퍼플 톤. */
    @JvmField
    val Gear = IconLoader.getIcon("/icons/gear.svg", Jpa3dIcons::class.java)
}
