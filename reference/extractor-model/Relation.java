package com.analyzer.extractor.model;

public enum Relation {
    EXTENDS,
    IMPLEMENTS,
    HAS_FIELD,
    PARAM,
    RETURNS,
    CALLS,
    NEW,
    ANNOTATED_BY,

    // JPA 연관관계 — ERD 뷰 전용. 필드의 @OneToMany/@ManyToOne/@OneToOne/@ManyToMany 어노테이션에서 도출.
    ONE_TO_MANY,
    MANY_TO_ONE,
    ONE_TO_ONE,
    MANY_TO_MANY,

    // Spring Data Repository → Entity. Repository 인터페이스의 첫 제네릭 타입 파라미터에서 도출.
    USES_ENTITY
}
