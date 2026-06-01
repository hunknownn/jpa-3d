package com.jpa3d.analyzer

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.jpa3d.model.ColumnInfo

/**
 * Kotlin 엔티티의 언어 특성이 DDL 모델에 올바르게 반영되는지 검증한다.
 *
 * 기존 테스트는 모두 Java 소스(`myFixture.addClass`)였다. Kotlin 특유의 동작
 * (non-null 타입, 주생성자 프로퍼티 등)은 실제 `.kt` 소스를 파싱해야 검증되므로
 * `addFileToProject` 로 Kotlin 파일을 프로젝트 스코프에 추가한다.
 *
 * 번들 Kotlin 플러그인(build.gradle.kts 의 `bundledPlugin("org.jetbrains.kotlin")`)이
 * 로드돼 있어야 UAST 가 Kotlin 을 PSI 로 풀 수 있다.
 */
class KotlinSupportTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        JpaStubs.install(myFixture)
    }

    private fun analyzeColumns(fqn: String): List<ColumnInfo> =
        Jpa3dAnalyzer(project).analyze().nodes
            .firstOrNull { it.id == fqn }?.entity?.columns
            ?: throw AssertionError("entity '$fqn' not found")

    private fun List<ColumnInfo>.byName(name: String): ColumnInfo =
        firstOrNull { it.columnName == name }
            ?: throw AssertionError("column '$name' not found (got ${map { it.columnName }})")

    // ===================================================================================
    // 스모크 — Kotlin 소스가 애초에 파싱/분석되는가
    // ===================================================================================

    fun testKotlinEntityIsAnalyzed() {
        myFixture.addFileToProject(
            "Member.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Entity
            class Member {
                @Id var id: Long? = null
                var name: String = ""
            }
            """.trimIndent()
        )
        val cols = analyzeColumns("com.example.Member")
        assertNotNull("Kotlin 엔티티가 분석되지 않음", cols.firstOrNull { it.columnName == "name" })
    }

    // ===================================================================================
    // nullability — 실제 Kotlin non-null/nullable 타입으로 재검증
    // ===================================================================================

    fun testKotlinNonNullReferenceIsNotNull() {
        myFixture.addFileToProject(
            "Member.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Entity
            class Member {
                @Id var id: Long? = null
                var name: String = ""
                var nickname: String? = null
            }
            """.trimIndent()
        )
        val cols = analyzeColumns("com.example.Member")
        assertFalse("Kotlin non-null String 은 NOT NULL 이어야", cols.byName("name").nullable)
        assertTrue("Kotlin nullable String? 은 nullable 이어야", cols.byName("nickname").nullable)
    }

    fun testKotlinNonNullPrimitiveIsNotNull() {
        myFixture.addFileToProject(
            "Counter.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Entity
            class Counter {
                @Id var id: Long? = null
                var count: Int = 0
                var ratio: Double? = null
            }
            """.trimIndent()
        )
        val cols = analyzeColumns("com.example.Counter")
        assertFalse("Kotlin non-null Int 는 NOT NULL 이어야", cols.byName("count").nullable)
        assertTrue("Kotlin nullable Double? 은 nullable 이어야", cols.byName("ratio").nullable)
    }

    // ===================================================================================
    // 주생성자 프로퍼티 — Kotlin 엔티티의 표준 형태. 어노테이션/필드가 잡혀야 함.
    // ===================================================================================

    fun testPrimaryConstructorPropertiesAreColumns() {
        myFixture.addFileToProject(
            "User.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Entity
            class User(
                @Id val id: Long = 0,
                @Column(name = "user_name") val name: String = "",
                val nickname: String? = null,
            )
            """.trimIndent()
        )
        val cols = analyzeColumns("com.example.User")
        // @Id, @Column(name=...) 어노테이션이 주생성자 프로퍼티에서 인식돼야 한다.
        assertTrue("@Id 가 주생성자 프로퍼티에서 인식 안 됨", cols.byName("id").primaryKey)
        assertEquals("@Column(name) 이 주생성자 프로퍼티에서 인식 안 됨",
            "user_name", cols.byName("user_name").columnName)
        assertFalse("주생성자 non-null String 은 NOT NULL 이어야", cols.byName("user_name").nullable)
        assertTrue("주생성자 nullable String? 은 nullable 이어야", cols.byName("nickname").nullable)
    }
}
