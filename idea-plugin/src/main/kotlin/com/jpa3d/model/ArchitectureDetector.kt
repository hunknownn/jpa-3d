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
     * 보조 신호 = 실제 빌드/IDE 모듈 수(realModuleCount): 단일 빌드 안에서 "진짜 멀티모듈"과
     *            "패키지로만 나눈 모놀리스"를 가른다.
     * 폴백 신호 = 모듈 간 JPA 엣지: 빌드 경계를 못 읽었을 때(settingsFileCount=0).
     *
     * | moduleCount | settingsFileCount | realModuleCount | → mode |
     * |---|---|---|---|
     * | ≤1 | — | — | MONOLITH |
     * | ≥2 | ≥2 (빌드/DB 분리) | — | MSA |
     * | ≥2 | 1 (단일 빌드 include) | ≥2 (진짜 서브프로젝트) | MODULAR_MONOLITH |
     * | ≥2 | 1 (단일 빌드) | ≤1 (패키지로만 분리) | MONOLITH |
     * | ≥2 | 0 (미상) | — | 모듈 간 JPA 엣지 있으면 MODULAR_MONOLITH, 없으면 MSA |
     *
     * @param moduleCount         서로 다른 논리 모듈 수(패키지 폴백 포함).
     * @param realModuleCount     실제 IDE/Gradle 모듈 수(패키지 폴백 제외). [ModuleResolver.distinctRealModules].
     * @param settingsFileCount   프로젝트 내 settings.gradle(.kts) 파일 수. 못 셌으면 0.
     * @param hasCrossModuleJpaEdge 모듈 경계를 넘는 진짜 JPA 관계 존재 여부.
     */
    fun detect(
        moduleCount: Int,
        realModuleCount: Int,
        settingsFileCount: Int,
        hasCrossModuleJpaEdge: Boolean
    ): ArchitectureMode = when {
        moduleCount <= 1 -> ArchitectureMode.MONOLITH
        settingsFileCount >= 2 -> ArchitectureMode.MSA
        // 단일 빌드: 실제 서브프로젝트가 둘 이상일 때만 모듈러 모놀리스.
        // 빌드 모듈은 하나인데 패키지로만 나눈 경우는 그냥 모놀리스(패키지 = 모듈 아님).
        settingsFileCount == 1 -> if (realModuleCount >= 2) ArchitectureMode.MODULAR_MONOLITH else ArchitectureMode.MONOLITH
        // settings 미상(0): 패키지 모듈 + 엣지 신호로 폴백.
        hasCrossModuleJpaEdge -> ArchitectureMode.MODULAR_MONOLITH
        else -> ArchitectureMode.MSA
    }

    /**
     * 엣지를 양끝 노드의 모듈로 3종 분류.
     *  - 같은 모듈 → [EdgeBoundary.INTRA]
     *  - 다른 모듈 + [Relation.SOFT_REF] → [EdgeBoundary.CROSS_SOFT]
     *  - 다른 모듈 + **진짜 JPA 관계**(ManyToOne 등) → [EdgeBoundary.CROSS_FK]
     *  - 다른 모듈 + 구조 엣지(EXTENDS/IMPLEMENTS/USES_ENTITY) → [EdgeBoundary.INTRA] (배경 처리)
     *  - 한쪽이라도 모듈 미상(null) → null (분류 보류)
     *
     * 경계를 넘는 상속(`@MappedSuperclass` 공유 베이스)이나 Repository 참조는 "모듈 간 결합" 신호가
     * 아니므로 CROSS_FK 로 강조하지 않는다 — 공유 커널(common) 패턴에서 진짜 FK 결합이 상속 노이즈에
     * 묻히는 것을 막는다. (demo 검증에서 발견: BaseEntity/AuditableEntity 상속이 CROSS_FK 를 오염.)
     */
    fun classifyBoundary(sourceModule: String?, targetModule: String?, relation: Relation): EdgeBoundary? {
        if (sourceModule == null || targetModule == null) return null
        if (sourceModule == targetModule) return EdgeBoundary.INTRA
        return when {
            relation == Relation.SOFT_REF -> EdgeBoundary.CROSS_SOFT
            isRealJpaRelation(relation) -> EdgeBoundary.CROSS_FK
            else -> EdgeBoundary.INTRA // 경계 넘는 상속/Repository — 결합 신호 아님, 배경으로
        }
    }

    /** [relation] 이 모듈 간 감지에 쓰는 "진짜 JPA 관계" 인지. */
    fun isRealJpaRelation(relation: Relation): Boolean = relation in REAL_JPA_RELATIONS
}
