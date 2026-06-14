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
 * 엔드투엔드 스모크 — `samples/` 의 세 아키텍처 샘플(monolith / modular-monolith / msa)을
 * 각각 분석기에 통과시켜 아키텍처 감지 + 엣지 경계 분류가 케이스별로 올바른지 검증한다.
 *
 * 여러 소스 루트(서브프로젝트/서비스)를 한 light fixture 에 모으기 위해, 디스크의 .java 를 읽어
 * 패키지 선언 기준 경로로 [addFileToProject] 한다. settings.gradle 개수(빌드 경계) 1차 신호는
 * fixture 에 없으므로(=0) JPA-엣지 폴백 경로로 판정된다.
 */
class SampleProjectsVerificationTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        JpaStubs.install(myFixture)
    }

    /** 작업 디렉터리(repo root 또는 idea-plugin) 어느 쪽에서 실행돼도 samples 하위 경로를 찾는다. */
    private fun sampleDir(rel: String): File =
        listOf(File("samples/$rel"), File("../samples/$rel"))
            .firstOrNull { it.isDirectory }
            ?: error("sample '$rel' not found from ${File(".").absolutePath}")

    /** 샘플 디렉터리의 모든 .java 를 패키지 선언 기준 경로로 fixture 에 추가. */
    private fun loadSample(rel: String) {
        sampleDir(rel).walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { f ->
                val text = f.readText()
                val pkg = Regex("""package\s+([\w.]+)""").find(text)?.groupValues?.get(1) ?: ""
                val relPath = (if (pkg.isEmpty()) "" else pkg.replace('.', '/') + "/") + f.name
                myFixture.addFileToProject(relPath, text)
            }
    }

    private fun report(tag: String, g: GraphData) {
        val entities = g.nodes.count { it.entity != null }
        println("=== SAMPLE $tag ===")
        println("architecture = ${g.architecture}")
        println("modules      = ${g.modules}")
        println("entities=$entities links=${g.links.size} boundary=${g.links.groupingBy { it.boundary }.eachCount()}")
        println("cross = " + g.links.filter { it.boundary != EdgeBoundary.INTRA && it.boundary != null }
            .map { "${it.source.substringAfterLast('.')}→${it.target.substringAfterLast('.')}:${it.boundary}" })
    }

    // === 1. 일반 모놀리식 — 단일 모듈 → MONOLITH, 경계 엣지 없음 ===
    fun testMonolith() {
        loadSample("monolith")
        val g = Jpa3dAnalyzer(project).analyze()
        report("monolith", g)

        assertEquals(ArchitectureMode.MONOLITH, g.architecture)
        // 도메인이 충분히 구체화돼 있어야 한다(엔티티 15개 이상).
        assertTrue("entities=${g.nodes.count { it.entity != null }}", g.nodes.count { it.entity != null } >= 15)
        // 단일 패키지 → 모듈 1개.
        assertEquals(1, g.modules.size)
        // 경계를 넘는 엣지가 없어야 한다.
        assertTrue(g.links.none { it.boundary == EdgeBoundary.CROSS_FK || it.boundary == EdgeBoundary.CROSS_SOFT })
        // 내부 관계(@ManyToOne)는 존재.
        assertTrue(g.links.any { it.boundary == EdgeBoundary.INTRA })
    }

    // === 2. 멀티모듈 모놀리식 — 모듈 간 진짜 @ManyToOne → MODULAR_MONOLITH, CROSS_FK ===
    fun testModularMonolith() {
        loadSample("modular-monolith")
        val g = Jpa3dAnalyzer(project).analyze()
        report("modular-monolith", g)

        assertEquals(ArchitectureMode.MODULAR_MONOLITH, g.architecture)
        assertTrue("entities=${g.nodes.count { it.entity != null }}", g.nodes.count { it.entity != null } >= 15)
        assertTrue("modules=${g.modules}", g.modules.containsAll(listOf("user", "catalog", "order")))
        // 모듈 경계를 넘는 진짜 FK 가 강조됨 (Order→User, OrderItem→Product).
        val crossFk = g.links.filter { it.boundary == EdgeBoundary.CROSS_FK }
        assertTrue("no CROSS_FK", crossFk.isNotEmpty())
        assertTrue(crossFk.all { ArchitectureDetectorIsRealJpa(it.relation) })
        // 약한참조는 없다(모두 진짜 FK).
        assertEquals(0, g.links.count { it.relation == Relation.SOFT_REF })
    }

    // === 3. MSA — 서비스 간 약한 ID 참조 → MSA, CROSS_SOFT ===
    fun testMsa() {
        loadSample("msa")
        val g = Jpa3dAnalyzer(project).analyze()
        report("msa", g)

        assertEquals(ArchitectureMode.MSA, g.architecture)
        assertTrue("entities=${g.nodes.count { it.entity != null }}", g.nodes.count { it.entity != null } >= 15)
        assertTrue("modules=${g.modules}", g.modules.containsAll(listOf("user", "product", "order")))
        // 서비스 경계를 넘는 약한 ID 참조가 점선(CROSS_SOFT)으로 잡힌다.
        val crossSoft = g.links.filter { it.boundary == EdgeBoundary.CROSS_SOFT }
        assertTrue("no CROSS_SOFT", crossSoft.isNotEmpty())
        assertTrue(crossSoft.all { it.relation == Relation.SOFT_REF })
        // 모듈 경계를 넘는 진짜 FK 는 없어야 한다(MSA 의 핵심).
        assertEquals(0, g.links.count { it.boundary == EdgeBoundary.CROSS_FK })
    }

    /** 진짜 JPA 관계 판정 — 테스트 가독성용 로컬 헬퍼. */
    private fun ArchitectureDetectorIsRealJpa(r: Relation): Boolean =
        r == Relation.ONE_TO_MANY || r == Relation.MANY_TO_ONE ||
            r == Relation.ONE_TO_ONE || r == Relation.MANY_TO_MANY
}
