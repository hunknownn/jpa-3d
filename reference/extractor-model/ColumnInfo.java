package com.analyzer.extractor.model;

/**
 * ERD 노드에 표시할 컬럼 한 개의 메타데이터.
 * JPA 어노테이션에서 추출된다. 어노테이션이 없는 필드는 기본값(필드명, nullable=true 등).
 *
 * @param fieldName        Java 필드명
 * @param columnName       @Column.name. 미지정이면 null (뷰어에서 fieldName 사용)
 * @param javaType         사람이 읽기 좋은 타입 (예: "java.lang.String", "long")
 * @param primaryKey       @Id 필드 여부
 * @param nullable         @Column.nullable (기본 true)
 * @param unique           @Column.unique (기본 false)
 * @param length           @Column.length (지정 안 됐으면 null)
 * @param generatedValue   @GeneratedValue.strategy 값 (AUTO/IDENTITY/SEQUENCE/TABLE), 없으면 null
 */
public record ColumnInfo(
        String fieldName,
        String columnName,
        String javaType,
        boolean primaryKey,
        boolean nullable,
        boolean unique,
        Integer length,
        String generatedValue
) {
}
