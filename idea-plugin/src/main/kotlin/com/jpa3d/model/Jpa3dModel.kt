package com.jpa3d.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * viewer 의 [types.ts] 와 1:1 매칭되는 데이터 모델.
 *
 * Jackson 기본 설정으로 직렬화하므로 필드명을 camelCase 그대로 유지한다.
 * null 필드는 `@JsonInclude(NON_NULL)` 로 직렬화에서 제외 — viewer 에서는 `?` 로 정의돼 있음.
 */

enum class Relation {
    EXTENDS,
    IMPLEMENTS,
    ONE_TO_MANY,
    MANY_TO_ONE,
    ONE_TO_ONE,
    MANY_TO_MANY,
    USES_ENTITY
}

enum class EntityKind(val jsonValue: String) {
    ENTITY("entity"),
    MAPPED_SUPERCLASS("mappedSuperclass"),
    EMBEDDABLE("embeddable")
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ColumnInfo(
    val fieldName: String,
    val columnName: String?,
    val javaType: String,
    val primaryKey: Boolean,
    val nullable: Boolean,
    val unique: Boolean,
    val length: Int?,
    val generatedValue: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InheritanceInfo(
    /** "SINGLE_TABLE" / "JOINED" / "TABLE_PER_CLASS" (JPA 기본은 SINGLE_TABLE). */
    val strategy: String,
    /** @DiscriminatorColumn(name=...) — 명시되지 않았으면 null. */
    val discriminatorColumn: String?,
    /** @DiscriminatorValue("...") — 자식 entity 에서 자신을 어떻게 식별하는지. */
    val discriminatorValue: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EntityInfo(
    val kind: String,      // EntityKind.jsonValue ("entity" / "mappedSuperclass" / "embeddable")
    val tableName: String?,
    val columns: List<ColumnInfo>,
    /** @Inheritance 가 붙은 상속 베이스 또는 그 자식. 아니면 null. */
    val inheritance: InheritanceInfo? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GraphNode(
    val id: String,                 // FQN
    val name: String,               // simple name
    val pkg: String,
    val kind: String,               // "class" / "interface"
    val stereotypes: List<String>,
    val entity: EntityInfo?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GraphLink(
    val source: String,
    val target: String,
    val relation: Relation,
    val weight: Int,
    val label: String?
)

data class GraphData(
    val seed: String,
    val depth: Int,
    val nodes: List<GraphNode>,
    val links: List<GraphLink>
)
