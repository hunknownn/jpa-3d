package com.jpa3d.model

/**
 * 아키텍처 모드 추론 + 엣지 경계 분류의 순수 로직 (Platform 의존 없음 — 단위 테스트 대상).
 *
 * Platform 신호(모듈 수, settings.gradle 개수, 노드 모듈)는 호출 측([Jpa3dAnalyzer])이 모아 넘기고,
 * 여기서는 그 신호만으로 순수하게 판정한다.
 */
object ArchitectureDetector {

    /**
     * 모듈 경계를 넘는 "진짜 JPA 관계" 로 보는 relation 집합 — 아키텍처 감지의 보조 신호용.
     * Repository 의 [Relation.USES_ENTITY] 나 상속([Relation.EXTENDS]/[Relation.IMPLEMENTS]) 은
     * 공유 영속성 컨텍스트의 증거가 아니므로 제외.
     */
    private val REAL_JPA_RELATIONS = setOf(
        Relation.ONE_TO_MANY, Relation.MANY_TO_ONE, Relation.ONE_TO_ONE, Relation.MANY_TO_MANY
    )

    /**
     * 아키텍처 모드 추론.
     *
     * 1차 신호 = 빌드 경계(settings.gradle 개수): 모놀리스 vs MSA 의 정의 그 자체.
     * 2차 신호 = 모듈 간 JPA 엣지: 빌드 경계를 못 읽었을 때(settingsFileCount=0)의 폴백.
     *
     * | moduleCount | settingsFileCount | → mode |
     * |---|---|---|
     * | ≤1 | — | MONOLITH |
     * | ≥2 | ≥2 (빌드/DB 분리) | MSA |
     * | ≥2 | 1 (단일 빌드 include) | MODULAR_MONOLITH |
     * | ≥2 | 0 (미상) | 모듈 간 JPA 엣지 있으면 MODULAR_MONOLITH, 없으면 MSA |
     *
     * @param moduleCount         서로 다른 논리 모듈 수.
     * @param settingsFileCount   프로젝트 내 settings.gradle(.kts) 파일 수. 못 셌으면 0.
     * @param hasCrossModuleJpaEdge 모듈 경계를 넘는 진짜 JPA 관계 존재 여부.
     */
    fun detect(
        moduleCount: Int,
        settingsFileCount: Int,
        hasCrossModuleJpaEdge: Boolean
    ): ArchitectureMode = when {
        moduleCount <= 1 -> ArchitectureMode.MONOLITH
        settingsFileCount >= 2 -> ArchitectureMode.MSA
        settingsFileCount == 1 -> ArchitectureMode.MODULAR_MONOLITH
        hasCrossModuleJpaEdge -> ArchitectureMode.MODULAR_MONOLITH
        else -> ArchitectureMode.MSA
    }

    /**
     * 엣지를 양끝 노드의 모듈로 3종 분류.
     *  - 같은 모듈 → [EdgeBoundary.INTRA]
     *  - 다른 모듈 + [Relation.SOFT_REF] → [EdgeBoundary.CROSS_SOFT] (PR3 에서 SOFT_REF 추가 시 활성)
     *  - 다른 모듈 + 그 외 → [EdgeBoundary.CROSS_FK]
     *  - 한쪽이라도 모듈 미상(null) → null (분류 보류)
     */
    fun classifyBoundary(sourceModule: String?, targetModule: String?, relation: Relation): EdgeBoundary? {
        if (sourceModule == null || targetModule == null) return null
        if (sourceModule == targetModule) return EdgeBoundary.INTRA
        return if (relation == Relation.SOFT_REF) EdgeBoundary.CROSS_SOFT else EdgeBoundary.CROSS_FK
    }

    /** [relation] 이 모듈 간 감지에 쓰는 "진짜 JPA 관계" 인지. */
    fun isRealJpaRelation(relation: Relation): Boolean = relation in REAL_JPA_RELATIONS
}
