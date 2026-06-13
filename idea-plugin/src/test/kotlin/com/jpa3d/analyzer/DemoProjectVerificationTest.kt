package com.jpa3d

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.jpa3d.analyzer.Jpa3dAnalyzer
import com.jpa3d.analyzer.JpaStubs
import com.jpa3d.model.ArchitectureMode
import com.jpa3d.model.EdgeBoundary
import com.jpa3d.model.GraphData
import com.jpa3d.model.Relation
import java.io.File

/**
 * 엔드투엔드 스모크 — 실제 `demo/` 샘플 프로젝트(패키지 기반 모듈러 모놀리스)의 소스를 통째로
 * 분석기에 통과시켜 module/architecture/boundary 산출을 검증한다.
 *
 * GUI(JCEF 3D 캔버스)를 띄우지 않고도 "데모에서 기능이 동작하는가" 를 데이터 레벨에서 확인하는
 * 재현 가능한 검증. demo 디렉터리를 testData 로 삼아 com 트리를 복사한 뒤 [Jpa3dAnalyzer] 실행.
 */
class DemoProjectVerificationTest : LightJavaCodeInsightFixtureTestCase() {

    /** demo 소스 루트 — 테스트 작업 디렉터리(idea-plugin or repo root) 어느 쪽이든 찾는다. */
    private val demoJavaRoot: File by lazy {
        listOf("demo/src/main/java", "../demo/src/main/java")
            .map { File(it) }
            .firstOrNull { it.isDirectory }
            ?: error("demo source root not found from ${File(".").absolutePath}")
    }

    override fun getTestDataPath(): String = demoJavaRoot.absolutePath

    override fun setUp() {
        super.setUp()
        JpaStubs.install(myFixture)
        // demo 의 com.example.shop.* 전체를 인메모리 프로젝트로 복사.
        myFixture.copyDirectoryToProject("com", "com")
    }

    fun testDemoIsAnalyzedAsModularMonolith() {
        val graph: GraphData = Jpa3dAnalyzer(project).analyze()

        // === 검증 리포트 (테스트 로그로 확인) ===
        val entities = graph.nodes.filter { it.entity != null }
        val boundaryHist = graph.links.groupingBy { it.boundary }.eachCount()
        val crossFk = graph.links.filter { it.boundary == EdgeBoundary.CROSS_FK }
        println("=== DEMO VERIFICATION ===")
        println("architecture = ${graph.architecture}")
        println("modules      = ${graph.modules}")
        println("entities     = ${entities.size}, links = ${graph.links.size}")
        println("boundary     = $boundaryHist")
        println("cross-module FK (sample) = " + crossFk.take(8).map {
            "${it.source.substringAfterLast('.')}→${it.target.substringAfterLast('.')}"
        })

        // === 단언 ===
        // 1. 데모는 cross-package 진짜 FK 가 풍부한 패키지 모듈러 모놀리스.
        assertEquals(ArchitectureMode.MODULAR_MONOLITH, graph.architecture)

        // 2. 패키지별 도메인 모듈이 식별됐다.
        assertTrue(
            "expected domain modules, got ${graph.modules}",
            graph.modules.containsAll(listOf("order", "payment", "catalog", "user", "shipping"))
        )

        // 3. 충분한 엔티티가 잡혔다 (demo 엔티티/임베더블/매핑수퍼클래스 합).
        assertTrue("too few entities: ${entities.size}", entities.size >= 20)

        // 4. 모듈 경계를 넘는 진짜 FK(CROSS_FK)가 존재한다 — 모듈러 모놀리스의 핵심 신호.
        assertTrue("no CROSS_FK edges detected", crossFk.isNotEmpty())

        // 5. 모듈 내부 관계(INTRA)도 함께 존재 (양쪽 다 분류되는지).
        assertTrue(
            "no INTRA edges detected",
            graph.links.any { it.boundary == EdgeBoundary.INTRA }
        )

        // 6. 모든 엔티티 노드에 module 이 부여됐다.
        assertTrue(
            "some entities have no module",
            entities.all { it.module != null }
        )

        // 7. demo 에는 `<name>Id` 약한참조 컬럼이 없으므로 SOFT_REF 엣지는 없어야 한다(오탐 무존재).
        assertEquals(
            "unexpected SOFT_REF edges",
            0,
            graph.links.count { it.relation == Relation.SOFT_REF }
        )
    }
}
