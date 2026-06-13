package com.jpa3d.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SoftRefHeuristic] 순수 로직 단위 테스트 (Platform 의존 없음).
 */
class SoftRefHeuristicTest {

    private fun col(
        field: String,
        type: String = "java.lang.Long",
        pk: Boolean = false,
        fk: Boolean = false
    ) = ColumnInfo(
        fieldName = field, columnName = field, javaType = type,
        primaryKey = pk, nullable = true, unique = false, foreignKey = fk,
        length = null, generatedValue = null
    )

    // === baseName ===

    @Test fun extractsBaseFromCamelAndSnake() {
        assertEquals("user", SoftRefHeuristic.baseName("userId"))
        assertEquals("user", SoftRefHeuristic.baseName("user_id"))
        assertEquals("product", SoftRefHeuristic.baseName("productId"))
    }

    @Test fun rejectsNonPattern() {
        assertNull(SoftRefHeuristic.baseName("id"))      // 접미사만
        assertNull(SoftRefHeuristic.baseName("_id"))
        assertNull(SoftRefHeuristic.baseName("valid"))   // 소문자 "id" 는 패턴 아님
        assertNull(SoftRefHeuristic.baseName("name"))
    }

    // === isScalarPkType / pkTypeCompatible ===

    @Test fun scalarPkTypes() {
        assertTrue(SoftRefHeuristic.isScalarPkType("java.lang.Long"))
        assertTrue(SoftRefHeuristic.isScalarPkType("long"))
        assertTrue(SoftRefHeuristic.isScalarPkType("java.util.UUID"))
        assertEquals(false, SoftRefHeuristic.isScalarPkType("com.example.User"))
        assertEquals(false, SoftRefHeuristic.isScalarPkType("java.time.LocalDateTime"))
    }

    @Test fun primitiveBoxedCompatible() {
        assertTrue(SoftRefHeuristic.pkTypeCompatible("long", "java.lang.Long"))
        assertTrue(SoftRefHeuristic.pkTypeCompatible("java.lang.Integer", "int"))
        assertEquals(false, SoftRefHeuristic.pkTypeCompatible("java.lang.String", "java.lang.Long"))
    }

    // === buildSimpleNameIndex ===

    @Test fun dropsAmbiguousSimpleNames() {
        val idx = SoftRefHeuristic.buildSimpleNameIndex(
            setOf("com.a.User", "com.b.User", "com.a.Order")
        )
        // User 는 두 패키지에 있어 모호 → 제외, Order 만 남음.
        assertNull(idx["user"])
        assertEquals("com.a.Order", idx["order"])
    }

    // === extractLinks ===

    @Test fun emitsSoftRefForMatchingIdColumn() {
        val cols = mapOf(
            "com.shop.user.User" to listOf(col("id", pk = true)),
            "com.shop.order.Order" to listOf(col("id", pk = true), col("userId"))
        )
        val links = SoftRefHeuristic.extractLinks(cols, cols.keys)
        assertEquals(1, links.size)
        val l = links.first()
        assertEquals("com.shop.order.Order", l.source)
        assertEquals("com.shop.user.User", l.target)
        assertEquals(Relation.SOFT_REF, l.relation)
    }

    @Test fun skipsNonMatchingAndForeignKeyAndPkTypeMismatch() {
        val cols = mapOf(
            "com.shop.user.User" to listOf(col("id", pk = true)),
            "com.shop.order.Order" to listOf(
                col("id", pk = true),
                col("createdById"),                       // CreatedBy 엔티티 없음 → 스킵
                col("user", type = "com.shop.user.User", fk = true), // 진짜 FK → 스킵
                col("userId", type = "java.lang.String")  // User#id 는 Long → 타입 불일치 스킵
            )
        )
        val links = SoftRefHeuristic.extractLinks(cols, cols.keys)
        assertTrue(links.isEmpty())
    }

    @Test fun skipsSelfReference() {
        // Category.parentId 가 우연히 Category 엔티티(있다 치고)를 가리키는 자기참조는 제외.
        val cols = mapOf(
            "com.shop.cat.Parent" to listOf(col("id", pk = true), col("parentId"))
        )
        val links = SoftRefHeuristic.extractLinks(cols, cols.keys)
        assertTrue(links.isEmpty())
    }
}
