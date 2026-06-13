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
    USES_ENTITY,
    /** 모듈 경계를 넘는 약한 ID 참조 (`userId: Long` → User). 엣지 emit 은 PR3. */
    SOFT_REF
}

enum class EntityKind(val jsonValue: String) {
    ENTITY("entity"),
    MAPPED_SUPERCLASS("mappedSuperclass"),
    EMBEDDABLE("embeddable")
}

/**
 * 프로젝트의 아키텍처 형태. [com.jpa3d.model.ArchitectureDetector] 가 추론한다.
 * viewer 의 엣지 강조 기본값을 가른다 (MSA→약한 참조 점선 위주, MODULAR_MONOLITH→경계 FK 강조).
 */
enum class ArchitectureMode { MONOLITH, MODULAR_MONOLITH, MSA }

/**
 * 엣지가 모듈 경계를 어떻게 넘는지의 분류 (엣지 3종).
 *  - [INTRA]: 같은 모듈 내부 관계.
 *  - [CROSS_FK]: 모듈 경계를 넘는 진짜 JPA 관계 (`@ManyToOne` 등).
 *  - [CROSS_SOFT]: 모듈 경계를 넘는 약한 ID 참조 (`userId: Long`, PR3 에서 추가).
 */
enum class EdgeBoundary { INTRA, CROSS_FK, CROSS_SOFT }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ColumnInfo(
    val fieldName: String,
    val columnName: String?,
    val javaType: String,
    val primaryKey: Boolean,
    val nullable: Boolean,
    /** @Column(unique=true) 또는 @Table(uniqueConstraints=...) 에 의해 유니크. */
    val unique: Boolean,
    /** @Table(indexes=...) 의 columnList 에 포함된 컬럼. PK 는 별도(고유 indexed=false 로 둠). */
    val indexed: Boolean = false,
    /** @ManyToOne / @OneToOne(owning side) 의 FK 컬럼. 엣지로도 함께 emit 되며 여긴 표 표기용. */
    val foreignKey: Boolean = false,
    /** FK 가 참조하는 entity FQN. foreignKey=true 일 때만. */
    val fkTarget: String? = null,
    val length: Int?,
    val generatedValue: String?,
    /** `@Column(precision=N)` — 주로 BigDecimal 에서 의미. */
    val precision: Int? = null,
    /** `@Column(scale=N)` — 주로 BigDecimal 에서 의미. */
    val scale: Int? = null,
    /** `@Lob` 어노테이션 유무. CLOB/BLOB 매핑에 사용. */
    val lob: Boolean = false,
    /**
     * `@GeneratedValue(strategy=SEQUENCE, generator="X")` + `@SequenceGenerator(name="X", sequenceName="Y")`
     * 에서 추출한 시퀀스 객체의 실제 이름. strategy=SEQUENCE 인데 generator 가 비어있으면 Hibernate 기본
     * "hibernate_sequence".
     */
    val sequenceName: String? = null,
    /**
     * 필드 타입이 enum 일 때 `@Enumerated` 매핑 전략. "STRING" → VARCHAR, "ORDINAL" → 정수.
     * `@Enumerated` 미지정 enum 은 JPA 기본 "ORDINAL". enum 이 아니면 null.
     */
    val enumType: String? = null,
    /**
     * `@Temporal` 매핑 종류 ("DATE" / "TIME" / "TIMESTAMP"). java.util.Date / Calendar 에만 의미 —
     * java.time.* 은 타입 자체로 매핑되므로 null.
     */
    val temporalType: String? = null,
    /**
     * `@Column(columnDefinition=...)` — DB 특정 raw 컬럼 정의. 있으면 타입 추론 대신 이 값을 그대로 쓴다.
     * 방언 중립이 아니므로 DDL 에 경고 주석과 함께 출력.
     */
    val columnDefinition: String? = null
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
    /** `@Table(schema=...)`. DDL 에서 `schema.table` 로 한정. 미지정이면 null. */
    val schema: String? = null,
    val columns: List<ColumnInfo>,
    /** @Inheritance 가 붙은 상속 베이스 또는 그 자식. 아니면 null. */
    val inheritance: InheritanceInfo? = null,
    /** `@Table(indexes=...)` 의 복합 인덱스 (size > 1 만). 단일 컬럼은 [ColumnInfo.indexed] 로. */
    val compositeIndexes: List<List<String>> = emptyList(),
    /** `@Table(uniqueConstraints=...)` 의 복합 유니크 (size > 1 만). 단일은 [ColumnInfo.unique] 로. */
    val compositeUniques: List<List<String>> = emptyList()
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GraphNode(
    val id: String,                 // FQN
    val name: String,               // simple name
    val pkg: String,
    val kind: String,               // "class" / "interface"
    val stereotypes: List<String>,
    val entity: EntityInfo?,
    /**
     * 노드가 속한 논리 모듈. [com.jpa3d.model.ModuleResolver] 가 부여한다 — IntelliJ/Gradle
     * 모듈명(정규화) 우선, 단일 모듈 프로젝트면 공통 패키지 이후의 도메인 세그먼트로 폴백.
     * viewer 의 공간 그룹핑/필터 키. 미산출(빈 프로젝트 등) 시 null.
     */
    val module: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GraphLink(
    val source: String,
    val target: String,
    val relation: Relation,
    val weight: Int,
    val label: String?,
    /**
     * MANY_TO_MANY owning side(mappedBy 없는 쪽) 여부. 조인 테이블 DDL 을 이 쪽에서만 생성해
     * 양방향 관계의 중복 생성을 막는다. 다른 관계나 inverse side 는 false.
     */
    val manyToManyOwning: Boolean = false,
    /** owning @ManyToMany 의 `@JoinTable(name=...)`. 미지정이면 null → DDL 에서 기본 규칙으로 생성. */
    val joinTableName: String? = null,
    /** `@JoinTable(joinColumns=@JoinColumn(name=...))` — owner 측 FK 컬럼명. 미지정이면 null. */
    val joinColumnName: String? = null,
    /** `@JoinTable(inverseJoinColumns=@JoinColumn(name=...))` — target 측 FK 컬럼명. self-ref M:N 에 필수. */
    val inverseJoinColumnName: String? = null,
    /**
     * 모듈 경계 분류. [com.jpa3d.analyzer.Jpa3dAnalyzer] 가 양끝 노드의 [GraphNode.module] 을 비교해
     * 채운다. 모듈 미상이면 null. viewer 의 엣지 스타일(INTRA 배경/CROSS_FK 강조/CROSS_SOFT 점선) 키.
     */
    val boundary: EdgeBoundary? = null
)

data class GraphData(
    val seed: String,
    val depth: Int,
    val nodes: List<GraphNode>,
    val links: List<GraphLink>,
    /** IDE 가 dumb mode (인덱싱 진행 중) 라 분석을 미룬 상태. viewer 는 자동 retry. */
    val indexing: Boolean = false,
    /** 추론된 아키텍처 형태. 분석 결과가 없으면(빈 프로젝트) null. */
    val architecture: ArchitectureMode? = null,
    /** 등장한 논리 모듈명 목록 (정렬·중복 제거). viewer 의 모듈 필터 칩 소스. */
    val modules: List<String> = emptyList()
)
