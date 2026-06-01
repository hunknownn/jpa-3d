package com.jpa3d.export

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.jpa3d.analyzer.Jpa3dAnalyzer
import com.jpa3d.analyzer.JpaStubs

/**
 * JPA 속성 → DDL 커버리지 매트릭스.
 *
 * 각 테스트는 해당 JPA 속성이 **올바르게** 변환됐을 때의 DDL 을 기대값으로 둔다.
 *   - 통과 = analyzer/exporter 가 그 속성을 제대로 지원
 *   - 실패 = 갭 (현재는 대개 sqlType else 브랜치로 VARCHAR(255) fallback)
 *
 * "모든 JPA 속성에서 DDL 이 오류 없이 정확히 나오는가" 를 한 곳에서 추적하기 위한 회귀 그물.
 */
class JpaAttributeCoverageTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        JpaStubs.install(myFixture)
    }

    private fun ddl(dialect: DdlDialect = DdlDialect.POSTGRES): String {
        val graph = Jpa3dAnalyzer(project).analyze()
        val model = ExportConverter.toExportModel(graph, ExportScope.ALL, seed = "", depth = 0)
        return DdlExporter(dialect, snakeCase = true).render(model)
            .replace("\"", "").replace("`", "")
    }

    private fun column(sql: String, name: String): String? =
        sql.lines().map { it.trim().removeSuffix(",") }
            .firstOrNull { it.startsWith("$name ") }

    // ===================================================================================
    // @Enumerated
    // ===================================================================================

    fun testEnumeratedStringMapsToVarchar() {
        addEnum()
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Task {
                @Id Long id;
                @Enumerated(EnumType.STRING) Status status;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "status")
        assertNotNull("status 컬럼 없음", line)
        assertTrue("STRING enum 은 VARCHAR 여야: $line", line!!.contains("VARCHAR"))
    }

    fun testEnumeratedOrdinalMapsToInteger() {
        addEnum()
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Task {
                @Id Long id;
                @Enumerated(EnumType.ORDINAL) Status priority;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "priority")
        assertNotNull("priority 컬럼 없음", line)
        assertTrue("ORDINAL enum 은 정수형이어야 (INTEGER/SMALLINT): $line",
            line!!.contains("INTEGER") || line.contains("SMALLINT"))
    }

    fun testBareEnumDefaultsToOrdinalInteger() {
        // @Enumerated 미지정 enum 필드 — JPA 기본은 ORDINAL → 정수.
        addEnum()
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Task {
                @Id Long id;
                Status status;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "status")
        assertNotNull("status 컬럼 없음", line)
        assertTrue("@Enumerated 없는 enum 은 ORDINAL 기본 → 정수형이어야: $line",
            line!!.contains("INTEGER") || line.contains("SMALLINT"))
    }

    // ===================================================================================
    // @Temporal + java.util.Date
    // ===================================================================================

    fun testTemporalDateMapsToDate() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Event {
                @Id Long id;
                @Temporal(TemporalType.DATE) java.util.Date day;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "day")
        assertNotNull("day 컬럼 없음", line)
        assertTrue("@Temporal(DATE) 는 DATE 여야: $line", line!!.contains("DATE"))
    }

    fun testTemporalTimestampMapsToTimestamp() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Event {
                @Id Long id;
                @Temporal(TemporalType.TIMESTAMP) java.util.Date createdAt;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "created_at")
        assertNotNull("created_at 컬럼 없음", line)
        assertTrue("@Temporal(TIMESTAMP) 는 TIMESTAMP 여야: $line", line!!.contains("TIMESTAMP"))
    }

    // ===================================================================================
    // @Embedded / @Embeddable
    // ===================================================================================

    fun testEmbeddedFlattensColumns() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Embeddable public class Address {
                @Column(name = "street") String street;
                @Column(name = "city") String city;
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Company {
                @Id Long id;
                @Embedded Address address;
            }
            """.trimIndent()
        )
        val sql = ddl()
        // 임베디드 필드가 호스트 테이블에 인라인돼야 — address 단일 컬럼이 아니라 street/city.
        assertNotNull("street 컬럼 펼침 안 됨:\n$sql", column(sql, "street"))
        assertNotNull("city 컬럼 펼침 안 됨:\n$sql", column(sql, "city"))
        assertNull("@Embedded 가 단일 컬럼으로 잘못 떨어짐:\n$sql", column(sql, "address"))
    }

    // ===================================================================================
    // @ElementCollection — 별도 컬렉션 테이블이라 호스트 컬럼이 생기면 안 됨
    // ===================================================================================

    fun testElementCollectionDoesNotProduceHostColumn() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Article {
                @Id Long id;
                @ElementCollection java.util.List<String> tags;
            }
            """.trimIndent()
        )
        val sql = ddl()
        assertNull("@ElementCollection 이 호스트 컬럼으로 잘못 떨어짐:\n$sql", column(sql, "tags"))
    }

    // ===================================================================================
    // @Version — 정수형 그대로 (지원 확인용)
    // ===================================================================================

    fun testVersionColumnMapsToIntegralType() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Account {
                @Id Long id;
                @Version Long version;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "version")
        assertNotNull("version 컬럼 없음", line)
        assertTrue("@Version Long 은 BIGINT 여야: $line", line!!.contains("BIGINT"))
    }

    // ===================================================================================
    // 복합 PK — @EmbeddedId / @IdClass
    // ===================================================================================

    fun testEmbeddedIdProducesCompositePk() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Embeddable public class OrderItemId {
                @Column(name = "order_id") Long orderId;
                @Column(name = "product_id") Long productId;
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class OrderItem {
                @EmbeddedId OrderItemId id;
                @Column(nullable = false) Integer quantity;
            }
            """.trimIndent()
        )
        val sql = ddl()
        // EmbeddedId 의 두 필드가 PK 컬럼으로 펼쳐지고 복합 PK 를 구성해야.
        assertNotNull("order_id 컬럼 펼침 안 됨:\n$sql", column(sql, "order_id"))
        assertNotNull("product_id 컬럼 펼침 안 됨:\n$sql", column(sql, "product_id"))
        assertNull("EmbeddedId 가 단일 컬럼으로 잘못 떨어짐:\n$sql", column(sql, "id"))
        assertTrue("복합 PK 누락:\n$sql", sql.contains("PRIMARY KEY (order_id, product_id)"))
    }

    fun testIdClassProducesCompositePk() {
        myFixture.addClass("package com.example; public class OrderKey { Long order; Long product; }")
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity @IdClass(OrderKey.class) public class LineItem {
                @Id @Column(name = "order_id") Long order;
                @Id @Column(name = "product_id") Long product;
                Integer quantity;
            }
            """.trimIndent()
        )
        val sql = ddl()
        assertTrue("@IdClass 복합 PK 누락:\n$sql", sql.contains("PRIMARY KEY (order_id, product_id)"))
    }

    // ===================================================================================
    // @Table(schema=...) — 스키마 한정 테이블명
    // ===================================================================================

    fun testTableSchemaQualifiesTableName() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity @Table(name = "orders", schema = "sales")
            public class Order {
                @Id Long id;
                @ManyToOne @JoinColumn(name = "customer_id") Customer customer;
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity @Table(name = "customers", schema = "sales")
            public class Customer { @Id Long id; }
            """.trimIndent()
        )
        val sql = ddl()
        assertTrue("스키마 한정 CREATE 누락:\n$sql", sql.contains("CREATE TABLE sales.orders"))
        assertTrue("FK 의 스키마 한정 참조 누락:\n$sql",
            Regex("ALTER TABLE sales.orders .*REFERENCES sales.customers").containsMatchIn(sql))
    }

    // ===================================================================================
    // @Column(columnDefinition=...) — raw 정의 보존 + 경고 주석 (방식 C)
    // ===================================================================================

    fun testColumnDefinitionIsPreservedRawWithWarning() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Doc {
                @Id Long id;
                @Column(columnDefinition = "JSONB DEFAULT '{}'") String payload;
            }
            """.trimIndent()
        )
        val sql = ddl()
        val line = column(sql, "payload")
        assertNotNull("payload 컬럼 없음", line)
        // 타입 추론(VARCHAR) 대신 raw 정의가 그대로 쓰여야
        assertTrue("columnDefinition raw 보존 안 됨: $line", line!!.contains("JSONB DEFAULT '{}'"))
        assertFalse("타입 추론이 잘못 끼어듦: $line", line.contains("VARCHAR"))
        // 방언 비중립 경고 주석
        assertTrue("경고 주석 누락:\n$sql", sql.contains("columnDefinition is DB-specific"))
    }

    // ===================================================================================
    // nullability 추론 — primitive / @NotNull(Kotlin non-null) → NOT NULL
    // ===================================================================================

    fun testPrimitiveFieldIsNotNull() {
        // JVM primitive (Java int, Kotlin non-null Int) 은 본질적으로 non-null → NOT NULL.
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Counter {
                @Id Long id;
                int count;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "count")
        assertNotNull("count 컬럼 없음", line)
        assertTrue("primitive int 는 NOT NULL 이어야: $line", line!!.contains("NOT NULL"))
    }

    fun testBoxedReferenceFieldStaysNullable() {
        // 박싱 타입(Integer) / 어노테이션 없는 참조 타입은 nullable 유지.
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Counter {
                @Id Long id;
                Integer count;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "count")
        assertNotNull("count 컬럼 없음", line)
        assertFalse("박싱 Integer 는 nullable 이어야: $line", line!!.contains("NOT NULL"))
    }

    fun testNotNullAnnotatedFieldIsNotNull() {
        // Kotlin non-null 참조 타입(`String`) 은 light class 가 타입/필드에 @NotNull 을 부여한다.
        // Java 에서 동일 어노테이션을 붙여 그 경로를 검증.
        myFixture.addClass("package org.jetbrains.annotations; public @interface NotNull { }")
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            import org.jetbrains.annotations.NotNull;
            @Entity public class Member {
                @Id Long id;
                @NotNull String name;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "name")
        assertNotNull("name 컬럼 없음", line)
        assertTrue("@NotNull(Kotlin non-null) 참조 타입은 NOT NULL 이어야: $line", line!!.contains("NOT NULL"))
    }

    fun testExplicitColumnNullableOverridesTypeInference() {
        // primitive 라도 @Column(nullable=true) 가 명시되면 그 값이 우선.
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity public class Counter {
                @Id Long id;
                @Column(nullable = true) int count;
            }
            """.trimIndent()
        )
        val line = column(ddl(), "count")
        assertNotNull("count 컬럼 없음", line)
        assertFalse("명시적 nullable=true 가 우선이어야: $line", line!!.contains("NOT NULL"))
    }

    private fun addEnum() {
        myFixture.addClass("package com.example; public enum Status { ACTIVE, INACTIVE, PENDING }")
    }
}
