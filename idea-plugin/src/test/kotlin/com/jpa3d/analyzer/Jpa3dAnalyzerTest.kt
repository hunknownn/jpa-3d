package com.jpa3d.analyzer

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.jpa3d.model.GraphData
import com.jpa3d.model.GraphNode

/**
 * Analyzer 단위 테스트 — IntelliJ Platform 의 light fixture 위에서 인메모리 Java 소스를
 * 추가하고 [Jpa3dAnalyzer.analyze] 를 직접 호출, 출력 [GraphData] 를 검증한다.
 *
 * 클래스 이름이 `*Test` 로 끝나야 IDEA gradle plugin 이 픽업.
 * JUnit 3 스타일 (`testXxx` 메서드 이름, `@Test` 없음) 로 vintage engine 이 인식.
 */
class Jpa3dAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        JpaStubs.install(myFixture)
    }

    // ===================================================================================
    // 헬퍼
    // ===================================================================================

    private fun analyze(): GraphData = Jpa3dAnalyzer(project).analyze()

    private fun GraphData.entity(fqn: String): GraphNode =
        nodes.firstOrNull { it.id == fqn }
            ?: throw AssertionError("entity '$fqn' not found in graph (got ${nodes.map { it.id }})")

    // ===================================================================================
    // 클래스 종류 감지
    // ===================================================================================

    fun testDetectsEntity() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class User { @Id Long id; }
            """.trimIndent()
        )
        val node = analyze().entity("com.example.User")
        assertEquals("entity", node.entity?.kind)
    }

    fun testDetectsMappedSuperclass() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @MappedSuperclass
            public class Auditable { @Id Long id; }
            """.trimIndent()
        )
        val node = analyze().entity("com.example.Auditable")
        assertEquals("mappedSuperclass", node.entity?.kind)
    }

    fun testDetectsEmbeddable() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Embeddable
            public class Address { String street; }
            """.trimIndent()
        )
        val node = analyze().entity("com.example.Address")
        assertEquals("embeddable", node.entity?.kind)
    }

    // ===================================================================================
    // 컬럼 메타데이터
    // ===================================================================================

    fun testIdAndGeneratedValueExtracted() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class User {
                @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
            }
            """.trimIndent()
        )
        val id = analyze().entity("com.example.User").entity!!.columns.first { it.fieldName == "id" }
        assertTrue(id.primaryKey)
        assertEquals("IDENTITY", id.generatedValue)
    }

    fun testColumnAttributesExtracted() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Article {
                @Id Long id;
                @Column(name = "title_text", length = 200, nullable = false, unique = true)
                String title;
                @Column(precision = 12, scale = 4)
                java.math.BigDecimal price;
            }
            """.trimIndent()
        )
        val cols = analyze().entity("com.example.Article").entity!!.columns
        val title = cols.first { it.fieldName == "title" }
        assertEquals("title_text", title.columnName)
        assertEquals(200, title.length)
        assertFalse(title.nullable)
        assertTrue(title.unique)
        val price = cols.first { it.fieldName == "price" }
        assertEquals(12, price.precision)
        assertEquals(4, price.scale)
    }

    fun testTransientFieldSkipped() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class User {
                @Id Long id;
                @Transient String cachedDisplay;
            }
            """.trimIndent()
        )
        val cols = analyze().entity("com.example.User").entity!!.columns
        assertNull(cols.firstOrNull { it.fieldName == "cachedDisplay" })
        assertNotNull(cols.firstOrNull { it.fieldName == "id" })
    }

    fun testLobFlag() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Article {
                @Id Long id;
                @Lob String body;
            }
            """.trimIndent()
        )
        val body = analyze().entity("com.example.Article").entity!!.columns.first { it.fieldName == "body" }
        assertTrue(body.lob)
    }

    // ===================================================================================
    // 관계 / FK 컬럼
    // ===================================================================================

    fun testManyToOneCreatesFKColumnAndRelation() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class User { @Id Long id; }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Purchase {
                @Id Long id;
                @ManyToOne @JoinColumn(name = "user_id", nullable = false) User user;
            }
            """.trimIndent()
        )
        val graph = analyze()
        // FK 컬럼이 Purchase 에 추가됨
        val fk = graph.entity("com.example.Purchase").entity!!.columns.first { it.foreignKey }
        assertEquals("user_id", fk.columnName)
        assertFalse(fk.nullable)
        assertEquals("com.example.User", fk.fkTarget)
        // 그래프에 MANY_TO_ONE 엣지
        val edge = graph.links.firstOrNull {
            it.source == "com.example.Purchase" && it.target == "com.example.User"
        }
        assertNotNull("MANY_TO_ONE edge missing", edge)
    }

    fun testOneToManyMappedBySkipsFKColumnOnInverseSide() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class User {
                @Id Long id;
                @OneToMany(mappedBy = "user") java.util.List<Purchase> purchases;
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Purchase {
                @Id Long id;
                @ManyToOne User user;
            }
            """.trimIndent()
        )
        val user = analyze().entity("com.example.User").entity!!
        // mappedBy 측에는 FK 컬럼이 안 생김
        assertNull(user.columns.firstOrNull { it.foreignKey })
    }

    // ===================================================================================
    // @Table — 이름 / 인덱스 / 유니크 (B1 회귀)
    // ===================================================================================

    fun testTableNameOverride() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity @Table(name = "user_accounts")
            public class User { @Id Long id; }
            """.trimIndent()
        )
        val node = analyze().entity("com.example.User")
        assertEquals("user_accounts", node.entity?.tableName)
    }

    fun testSingleColumnIndexFlag() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            @Table(indexes = @Index(columnList = "email"))
            public class User {
                @Id Long id;
                String email;
            }
            """.trimIndent()
        )
        val email = analyze().entity("com.example.User").entity!!.columns.first { it.fieldName == "email" }
        assertTrue("email column should have indexed flag", email.indexed)
    }

    fun testCompositeIndexPreservedAsGroup() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            @Table(indexes = @Index(columnList = "user_id, created_at"))
            public class Log {
                @Id Long id;
                @Column(name = "user_id") Long userId;
                @Column(name = "created_at") java.time.LocalDateTime createdAt;
            }
            """.trimIndent()
        )
        val entity = analyze().entity("com.example.Log").entity!!
        assertEquals(1, entity.compositeIndexes.size)
        assertEquals(listOf("user_id", "created_at"), entity.compositeIndexes[0])
    }

    fun testCompositeUniquePreservesOriginalCase() {
        // B1 회귀 — 분석기가 컬럼명을 lowercase 시키지 않아야 함.
        // 잘못된 동작 시 ["firstname", "lastname"] 로 저장되어 snake_case 변환된 컬럼과 매칭 실패.
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            @Table(uniqueConstraints = @UniqueConstraint(columnNames = {"firstName", "lastName"}))
            public class Person {
                @Id Long id;
                String firstName;
                String lastName;
            }
            """.trimIndent()
        )
        val entity = analyze().entity("com.example.Person").entity!!
        assertEquals(1, entity.compositeUniques.size)
        assertEquals(listOf("firstName", "lastName"), entity.compositeUniques[0])
    }

    // ===================================================================================
    // 상속
    // ===================================================================================

    fun testInheritanceStrategyOnRoot() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
            public class Animal { @Id Long id; }
            """.trimIndent()
        )
        val animal = analyze().entity("com.example.Animal").entity!!
        assertEquals("SINGLE_TABLE", animal.inheritance?.strategy)
    }

    fun testChildInheritsStrategyFromParent() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
            public class Animal { @Id Long id; }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity @DiscriminatorValue("DOG")
            public class Dog extends Animal { Integer barkLoudness; }
            """.trimIndent()
        )
        val dog = analyze().entity("com.example.Dog").entity!!
        assertEquals("SINGLE_TABLE", dog.inheritance?.strategy)
        assertEquals("DOG", dog.inheritance?.discriminatorValue)
    }

    fun testExtendsEdgeBetweenEntityAndParent() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Animal { @Id Long id; }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Dog extends Animal { String breed; }
            """.trimIndent()
        )
        val graph = analyze()
        val edge = graph.links.firstOrNull {
            it.source == "com.example.Dog" && it.target == "com.example.Animal" && it.relation.name == "EXTENDS"
        }
        assertNotNull("EXTENDS edge Dog → Animal missing", edge)
    }

    // ===================================================================================
    // @SequenceGenerator
    // ===================================================================================

    fun testSequenceGeneratorLinksByGeneratorName() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class User {
                @Id
                @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_gen")
                @SequenceGenerator(name = "user_gen", sequenceName = "USER_SEQ")
                Long id;
            }
            """.trimIndent()
        )
        val id = analyze().entity("com.example.User").entity!!.columns.first { it.primaryKey }
        assertEquals("SEQUENCE", id.generatedValue)
        assertEquals("USER_SEQ", id.sequenceName)
    }

    fun testSequenceGeneratorDefaultsToHibernateSequence() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class User {
                @Id @GeneratedValue(strategy = GenerationType.SEQUENCE) Long id;
            }
            """.trimIndent()
        )
        val id = analyze().entity("com.example.User").entity!!.columns.first { it.primaryKey }
        assertEquals("hibernate_sequence", id.sequenceName)
    }
}
