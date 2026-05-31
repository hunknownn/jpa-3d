package com.jpa3d.export

enum class DdlDialect(val displayName: String, val fileSuffix: String) {
    POSTGRES("PostgreSQL", "postgres"),
    MYSQL("MySQL", "mysql"),
    H2("H2", "h2"),
    ORACLE("Oracle", "oracle")
}

enum class ExportScope {
    /** 분석된 전체 그래프. */
    ALL,

    /** seed FQN + depth 로 BFS 한 부분 그래프. seed 가 비어 있으면 ALL 과 동일. */
    CURRENT_VIEW
}

/**
 * Export 다이얼로그가 모아 [ExportRunner] 에 넘기는 사용자 선택 모음.
 *
 * 사용자가 체크 안 한 포맷은 false 로 들어와 해당 exporter 가 건너뛴다.
 */
data class ExportOptions(
    val outputDir: java.nio.file.Path,
    val scope: ExportScope,
    val seed: String,
    val depth: Int,

    val formatJson: Boolean,
    val formatDdl: Boolean,
    val formatMermaid: Boolean,

    /** 현재 viewer 화면을 PNG 로 캡처 (2D/3D 모두 지원). */
    val snapshotPng: Boolean,

    /** 현재 viewer 화면을 SVG 로 캡처 (2D 에서만 지원 — 3D 면 캡처가 스킵됨). */
    val snapshotSvg: Boolean,

    val ddlDialect: DdlDialect,
    /** 테이블/컬럼 이름을 snake_case 로 변환 (Hibernate ImplicitNamingStrategy 기본 동작과 일치). */
    val ddlSnakeCase: Boolean = true,
    /** SQL 파일 머리에 `DROP TABLE IF EXISTS ...;` 문을 추가 — 재실행 안전성. */
    val ddlDropExisting: Boolean = false
) {
    fun anyFormatSelected(): Boolean =
        formatJson || formatDdl || formatMermaid || snapshotPng || snapshotSvg
}
