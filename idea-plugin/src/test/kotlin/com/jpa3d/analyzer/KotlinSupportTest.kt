package com.jpa3d.analyzer

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.jpa3d.export.DdlDialect
import com.jpa3d.export.DdlExporter
import com.jpa3d.export.ExportConverter
import com.jpa3d.export.ExportScope
import com.jpa3d.model.ColumnInfo
import com.jpa3d.model.Relation

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

    private fun ddl(): String {
        val graph = Jpa3dAnalyzer(project).analyze()
        val model = ExportConverter.toExportModel(graph, ExportScope.ALL, seed = "", depth = 0)
        return DdlExporter(DdlDialect.POSTGRES, snakeCase = true).render(model)
            .replace("\"", "").replace("`", "")
    }

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

    // ===================================================================================
    // enum class — Kotlin enum 도 isEnum 으로 풀려 ORDINAL/STRING 매핑이 돼야 함
    // ===================================================================================

    fun testKotlinEnumClassMapping() {
        myFixture.addFileToProject(
            "Status.kt",
            "package com.example\nenum class Status { ACTIVE, INACTIVE }"
        )
        myFixture.addFileToProject(
            "Task.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Entity
            class Task(
                @Id val id: Long = 0,
                @Enumerated(EnumType.STRING) val status: Status = Status.ACTIVE,
                val priority: Status = Status.ACTIVE,
            )
            """.trimIndent()
        )
        val cols = analyzeColumns("com.example.Task")
        assertEquals("@Enumerated(STRING) 미반영", "STRING", cols.byName("status").enumType)
        // @Enumerated 없는 enum 필드 → JPA 기본 ORDINAL. Kotlin enum class 도 isEnum 으로 잡혀야.
        assertEquals("bare Kotlin enum 은 ORDINAL 기본이어야", "ORDINAL", cols.byName("priority").enumType)
    }

    // ===================================================================================
    // @Embeddable data class — @Embedded 가 호스트 테이블에 펼쳐져야 함
    // ===================================================================================

    fun testKotlinEmbeddableDataClassFlattens() {
        myFixture.addFileToProject(
            "Address.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Embeddable
            data class Address(
                val street: String = "",
                val city: String = "",
            )
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "Company.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Entity
            class Company(
                @Id val id: Long = 0,
                @Embedded val address: Address = Address(),
            )
            """.trimIndent()
        )
        val cols = analyzeColumns("com.example.Company")
        assertNotNull("street 펼침 안 됨 (got ${cols.map { it.columnName }})",
            cols.firstOrNull { it.columnName == "street" })
        assertNotNull("city 펼침 안 됨 (got ${cols.map { it.columnName }})",
            cols.firstOrNull { it.columnName == "city" })
        assertNull("@Embedded 가 단일 컬럼으로 잘못 떨어짐", cols.firstOrNull { it.columnName == "address" })
        // data class 합성 멤버(componentN/copy)가 컬럼으로 새지 않아야.
        assertNull("data class 합성 멤버가 컬럼으로 샘", cols.firstOrNull { it.columnName?.startsWith("component") == true })
    }

    // ===================================================================================
    // MutableList 관계 — Kotlin 컬렉션 타입의 @OneToMany 관계가 인식돼야 함
    // ===================================================================================

    fun testKotlinMutableListRelation() {
        myFixture.addFileToProject(
            "Course.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Entity
            class Course(@Id val id: Long = 0)
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "Member.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Entity
            class Member(
                @Id val id: Long = 0,
                @OneToMany val courses: MutableList<Course> = mutableListOf(),
            )
            """.trimIndent()
        )
        val links = Jpa3dAnalyzer(project).analyze().links
        val link = links.firstOrNull { it.source == "com.example.Member" && it.target == "com.example.Course" }
        assertNotNull("MutableList<Course> @OneToMany 관계 누락 (got ${links.map { "${it.source}->${it.target}" }})", link)
        assertEquals("관계 종류 오인식", Relation.ONE_TO_MANY, link!!.relation)
    }

    // ===================================================================================
    // is-prefix Boolean — 필드 접근 기준 컬럼명. isActive → is_active (active 가 아님).
    // ===================================================================================

    fun testKotlinBooleanIsPrefixColumnName() {
        myFixture.addFileToProject(
            "Account.kt",
            """
            package com.example
            import jakarta.persistence.*
            @Entity
            class Account(
                @Id val id: Long = 0,
                val isActive: Boolean = false,
            )
            """.trimIndent()
        )
        // 모델 레벨: 필드명 그대로 isActive (getter 기반 active 로 깎이면 안 됨).
        val cols = analyzeColumns("com.example.Account")
        assertNotNull("isActive 컬럼 누락 (got ${cols.map { it.columnName }})",
            cols.firstOrNull { it.columnName == "isActive" })
        // DDL(snake_case): is_active 여야 함.
        val sql = ddl()
        assertTrue("is_active 컬럼이 나와야 (필드 접근 기준):\n$sql", sql.contains("is_active"))
    }

    // ===================================================================================
    // java.time.Instant — 타임존 인식 타임스탬프로 매핑돼야 함 (Postgres 기준)
    // ===================================================================================

    fun testKotlinInstantColumnType() {
        // 테스트 mockJDK 에는 java.time 이 없어 미해결되므로 스텁 주입 (실제 프로젝트엔 항상 존재).
        myFixture.addClass("package java.time; public final class Instant { }")
        myFixture.addFileToProject(
            "Event.kt",
            """
            package com.example
            import jakarta.persistence.*
            import java.time.Instant
            @Entity
            class Event(
                @Id val id: Long = 0,
                val createdAt: Instant? = null,
            )
            """.trimIndent()
        )
        // 모델: javaType 이 java.time.Instant 로 풀려야 sqlType 이 매핑됨.
        val col = analyzeColumns("com.example.Event").byName("createdAt")
        assertEquals("Instant 의 javaType 미해결", "java.time.Instant", col.javaType)
        // DDL(Postgres): TIMESTAMP(6) WITH TIME ZONE.
        val line = ddl().lines().map { it.trim().removeSuffix(",") }.first { it.startsWith("created_at ") }
        assertTrue("Instant 가 타임존 타임스탬프로 안 나옴: $line",
            line.contains("TIMESTAMP(6) WITH TIME ZONE"))
    }
}
