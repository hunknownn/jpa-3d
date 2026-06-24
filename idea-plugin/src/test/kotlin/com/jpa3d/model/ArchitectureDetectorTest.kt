package com.jpa3d.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ArchitectureDetector] 순수 로직 단위 테스트 (Platform 의존 없음).
 */
class ArchitectureDetectorTest {

    // === detect ===

    // detect(moduleCount, realModuleCount, settingsFileCount, hasCrossModuleJpaEdge)

    @Test fun singleModuleIsMonolith() {
        assertEquals(ArchitectureMode.MONOLITH, ArchitectureDetector.detect(1, 1, 1, false))
        assertEquals(ArchitectureMode.MONOLITH, ArchitectureDetector.detect(0, 0, 0, false))
    }

    @Test fun multipleBuildsIsMsa() {
        // 모듈 여럿 + settings.gradle 둘 이상 = 빌드/DB 분리 = MSA.
        assertEquals(ArchitectureMode.MSA, ArchitectureDetector.detect(3, 3, 3, false))
        // 모듈 간 JPA 엣지가 있어도(경계 침범) 빌드가 갈렸으면 MSA 로 본다.
        assertEquals(ArchitectureMode.MSA, ArchitectureDetector.detect(3, 3, 3, true))
    }

    @Test fun singleBuildMultipleRealModulesIsModularMonolith() {
        // 단일 settings.gradle + include(...) + 실제 서브프로젝트 둘 이상 = 모듈러 모놀리스.
        assertEquals(ArchitectureMode.MODULAR_MONOLITH, ArchitectureDetector.detect(4, 4, 1, false))
        assertEquals(ArchitectureMode.MODULAR_MONOLITH, ArchitectureDetector.detect(4, 4, 1, true))
    }

    @Test fun singleBuildPackageOnlySeparationIsMonolith() {
        // 단일 빌드 모듈인데 패키지로만 나뉜 경우(shop demo): 논리 모듈은 여럿이지만 실제 모듈은 1개 → 모놀리스.
        assertEquals(ArchitectureMode.MONOLITH, ArchitectureDetector.detect(9, 1, 1, false))
        assertEquals(ArchitectureMode.MONOLITH, ArchitectureDetector.detect(9, 1, 1, true))
        // 실제 모듈을 못 읽은(0) 경우도 모놀리스로 본다(패키지 모듈만 존재).
        assertEquals(ArchitectureMode.MONOLITH, ArchitectureDetector.detect(9, 0, 1, false))
    }

    @Test fun unknownBuildFallsBackToJpaEdgeSignal() {
        // settings 못 읽음(0): 모듈 간 JPA 엣지 있으면 모듈러 모놀리스, 없으면 MSA. (realModuleCount 무관)
        assertEquals(ArchitectureMode.MODULAR_MONOLITH, ArchitectureDetector.detect(2, 0, 0, true))
        assertEquals(ArchitectureMode.MSA, ArchitectureDetector.detect(2, 0, 0, false))
    }

    // === classifyBoundary ===

    @Test fun sameModuleIsIntra() {
        assertEquals(EdgeBoundary.INTRA,
            ArchitectureDetector.classifyBoundary("order", "order", Relation.MANY_TO_ONE))
    }

    @Test fun crossModuleRealRelationIsCrossFk() {
        assertEquals(EdgeBoundary.CROSS_FK,
            ArchitectureDetector.classifyBoundary("order", "user", Relation.MANY_TO_ONE))
    }

    @Test fun crossModuleSoftRefIsCrossSoft() {
        assertEquals(EdgeBoundary.CROSS_SOFT,
            ArchitectureDetector.classifyBoundary("order", "user", Relation.SOFT_REF))
    }

    @Test fun crossModuleStructuralEdgeIsNotEmphasized() {
        // 공유 베이스 상속/Repository 참조가 경계를 넘어도 결합 신호가 아니므로 CROSS_FK 로 강조하지 않는다.
        assertEquals(EdgeBoundary.INTRA,
            ArchitectureDetector.classifyBoundary("catalog", "common", Relation.EXTENDS))
        assertEquals(EdgeBoundary.INTRA,
            ArchitectureDetector.classifyBoundary("order", "user", Relation.USES_ENTITY))
    }

    @Test fun unknownModuleIsNull() {
        assertNull(ArchitectureDetector.classifyBoundary(null, "user", Relation.MANY_TO_ONE))
        assertNull(ArchitectureDetector.classifyBoundary("order", null, Relation.MANY_TO_ONE))
    }

    // === isRealJpaRelation ===

    @Test fun realJpaRelationsRecognized() {
        assertEquals(true, ArchitectureDetector.isRealJpaRelation(Relation.ONE_TO_MANY))
        assertEquals(true, ArchitectureDetector.isRealJpaRelation(Relation.MANY_TO_MANY))
        // Repository/상속/약한참조는 영속성 공유 증거가 아님.
        assertEquals(false, ArchitectureDetector.isRealJpaRelation(Relation.USES_ENTITY))
        assertEquals(false, ArchitectureDetector.isRealJpaRelation(Relation.EXTENDS))
        assertEquals(false, ArchitectureDetector.isRealJpaRelation(Relation.SOFT_REF))
    }
}
