package com.jpa3d

import com.intellij.openapi.util.IconLoader

/**
 * 플러그인 번들 아이콘. 라이트/다크 변형은 IconLoader 가 테마에 맞춰 `*_dark.svg` 를 자동 선택한다.
 */
object Jpa3dIcons {
    /** DB 실린더 모양 SQL 아이콘 — 엔티티 SQL 추출 진입점(거터/배너)에서 사용. */
    @JvmField
    val Sql = IconLoader.getIcon("/icons/sql.svg", Jpa3dIcons::class.java)
}
