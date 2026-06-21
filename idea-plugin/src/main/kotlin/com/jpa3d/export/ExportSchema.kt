package com.jpa3d.export

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 외부 도구에서 읽기 쉬운 export 전용 스키마.
 *
 * 내부 [com.jpa3d.model.GraphData] 와 별도로 정의된 이유:
 *   - GraphData 는 viewer 가 쓰는 nodes/links 구조라 외부 도구 입장에선 직관적이지 않다.
 *   - export 스키마는 "entities + relations" 의 평탄한 형태로 변경 빈도가 낮다 (안정 계약).
 *
 * 직렬화는 Jackson 기본 설정. `null` 필드는 제외해 JSON 을 가볍게 유지.
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportColumn(
    /** Java/Kotlin 필드 이름. */
    val name: String,
    /** `@Column(name=...)` 또는 fieldName 으로부터 추론된 DB 컬럼 이름. */
    val columnName: String,
    /**
     * [columnName] 이 `@Column(name=...)` 으로 **명시**된 물리 이름인지 여부.
     * true 면 DDL 에서 snake_case 변환 없이 그대로 사용 (JPA 가 명시 이름을 암묵 네이밍 전략에
     * 태우지 않는 것과 동일). false (fieldName 추론) 면 snake_case 변환 대상.
     */
    @get:JsonIgnore
    val columnNameExplicit: Boolean = false,
    /** FQN (예: java.lang.Long). */
    val javaType: String,
    val primaryKey: Boolean,
    val nullable: Boolean,
    val unique: Boolean,
    val indexed: Boolean,
    val foreignKey: Boolean,
    /** foreignKey=true 일 때 참조 대상 entity FQN. */
    val fkTarget: String? = null,
    val length: Int? = null,
    /** `@GeneratedValue.strategy` — IDENTITY / SEQUENCE / TABLE / AUTO / UUID 등. */
    val generatedValue: String? = null,
    /** `@Column(precision)` — BigDecimal 등 자릿수 표현. */
    val precision: Int? = null,
    /** `@Column(scale)` — BigDecimal 소수부 자릿수. */
    val scale: Int? = null,
    /** `@Lob` 여부. true 면 CLOB/BLOB 로 매핑. */
    val lob: Boolean = false,
    /** `@SequenceGenerator(sequenceName=...)` 로 추출한 실제 시퀀스 이름. SEQUENCE 전략에서만 의미. */
    val sequenceName: String? = null,
    /** enum 필드의 `@Enumerated` 전략 ("STRING"/"ORDINAL"). enum 이 아니면 null. */
    val enumType: String? = null,
    /** `@Temporal` 종류 ("DATE"/"TIME"/"TIMESTAMP"). java.util.Date/Calendar 에만 의미. */
    val temporalType: String? = null,
    /** `@Column(columnDefinition=...)` — DB 특정 raw 컬럼 정의. 있으면 타입 추론 대신 그대로 사용. */
    val columnDefinition: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportInheritance(
    val strategy: String,
    val discriminatorColumn: String? = null,
    val discriminatorValue: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportEntity(
    val fqn: String,
    val name: String,
    val `package`: String,
    /** "entity" / "mappedSuperclass" / "embeddable". */
    val kind: String,
    val tableName: String? = null,
    /**
     * [tableName] 이 `@Table(name=...)` 으로 **명시**된 물리 이름인지 여부.
     * true 면 DDL 에서 snake_case 변환 없이 그대로 사용. false (클래스명 추론) 면 변환 대상.
     */
    @get:JsonIgnore
    val tableNameExplicit: Boolean = false,
    /** `@Table(schema=...)`. DDL 에서 `schema.table` 로 한정. */
    val schema: String? = null,
    val inheritance: ExportInheritance? = null,
    val columns: List<ExportColumn>,
    /** 복합(2+ 컬럼) 인덱스 그룹. 단일 컬럼은 [ExportColumn.indexed] 로. */
    val compositeIndexes: List<List<String>> = emptyList(),
    /** 복합(2+ 컬럼) 유니크 제약 그룹. 단일 컬럼은 [ExportColumn.unique] 로. */
    val compositeUniques: List<List<String>> = emptyList(),
    /**
     * 참조 전용 스텁 — FK 대상으로만 모델에 포함된 엔티티. [DdlExporter] 는 이 엔티티의
     * CREATE TABLE / 인덱스 / 시퀀스 / 자체 FK 를 만들지 않고, **FK 대상 해석에만** 쓴다.
     * 단일 엔티티 추출([ExportConverter.toSingleEntityModel])에서 추출 범위 밖 연관 테이블이
     * 이미 존재한다고 가정하고 올바른 FK 제약을 내보내기 위한 장치.
     *
     * 내부 DDL 전용 플래그라 JSON 직렬화에서는 제외한다(안정 계약 유지).
     */
    @get:JsonIgnore
    val referenceOnly: Boolean = false
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportRelation(
    val source: String,
    val target: String,
    /** EXTENDS / IMPLEMENTS / ONE_TO_MANY / MANY_TO_ONE / ONE_TO_ONE / MANY_TO_MANY / USES_ENTITY. */
    val type: String,
    val label: String? = null,
    /** MANY_TO_MANY owning side 여부 — DDL 조인 테이블 생성 트리거. */
    val manyToManyOwning: Boolean = false,
    /** owning @ManyToMany 의 `@JoinTable(name=...)`. 미지정이면 null. */
    val joinTableName: String? = null,
    /** `@JoinTable(joinColumns=@JoinColumn(name=...))` — owner 측 FK 컬럼명. */
    val joinColumnName: String? = null,
    /** `@JoinTable(inverseJoinColumns=@JoinColumn(name=...))` — target 측 FK 컬럼명. */
    val inverseJoinColumnName: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportModel(
    val schemaVersion: Int,
    /** export 생성 시각 (ISO-8601, UTC). 도구가 재현성 추적에 사용. */
    val generatedAt: String,
    val entities: List<ExportEntity>,
    val relations: List<ExportRelation>
)
