package com.jpa3d.analyzer

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture

/**
 * 테스트 모듈에 `jakarta.persistence` 스텁 어노테이션/enum 을 인메모리로 주입한다.
 *
 * 진짜 jakarta.persistence-api jar 를 의존성으로 끌어오면 부트 비용이 늘고 버전 의존이 생긴다.
 * 스텁만으로도 analyzer 가 PSI 의 qualifiedName 매칭으로 동작하기엔 충분.
 */
internal object JpaStubs {

    fun install(fixture: JavaCodeInsightTestFixture) {
        // enum
        fixture.addClass("package jakarta.persistence; public enum GenerationType { TABLE, SEQUENCE, IDENTITY, AUTO, UUID }")
        fixture.addClass("package jakarta.persistence; public enum InheritanceType { SINGLE_TABLE, JOINED, TABLE_PER_CLASS }")
        fixture.addClass("package jakarta.persistence; public enum DiscriminatorType { STRING, CHAR, INTEGER }")
        fixture.addClass("package jakarta.persistence; public enum FetchType { LAZY, EAGER }")

        // 클래스 레벨 어노테이션
        fixture.addClass("package jakarta.persistence; public @interface Entity { String name() default \"\"; }")
        fixture.addClass("package jakarta.persistence; public @interface MappedSuperclass { }")
        fixture.addClass("package jakarta.persistence; public @interface Embeddable { }")
        fixture.addClass(
            "package jakarta.persistence; public @interface Table {" +
                " String name() default \"\";" +
                " String catalog() default \"\";" +
                " String schema() default \"\";" +
                " Index[] indexes() default {};" +
                " UniqueConstraint[] uniqueConstraints() default {};" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; public @interface Index {" +
                " String name() default \"\";" +
                " String columnList();" +
                " boolean unique() default false;" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; public @interface UniqueConstraint {" +
                " String name() default \"\";" +
                " String[] columnNames();" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; public @interface Inheritance {" +
                " InheritanceType strategy() default InheritanceType.SINGLE_TABLE;" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; public @interface DiscriminatorColumn {" +
                " String name() default \"DTYPE\";" +
                " DiscriminatorType discriminatorType() default DiscriminatorType.STRING;" +
                " int length() default 31;" +
                " }"
        )
        fixture.addClass("package jakarta.persistence; public @interface DiscriminatorValue { String value(); }")

        // 필드 레벨
        fixture.addClass("package jakarta.persistence; public @interface Id { }")
        fixture.addClass(
            "package jakarta.persistence; public @interface GeneratedValue {" +
                " GenerationType strategy() default GenerationType.AUTO;" +
                " String generator() default \"\";" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; public @interface Column {" +
                " String name() default \"\";" +
                " boolean nullable() default true;" +
                " boolean unique() default false;" +
                " int length() default 255;" +
                " int precision() default 0;" +
                " int scale() default 0;" +
                " }"
        )
        fixture.addClass("package jakarta.persistence; public @interface Transient { }")
        fixture.addClass(
            "package jakarta.persistence; public @interface JoinColumn {" +
                " String name() default \"\";" +
                " boolean nullable() default true;" +
                " boolean unique() default false;" +
                " String referencedColumnName() default \"\";" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; public @interface JoinTable {" +
                " String name() default \"\";" +
                " JoinColumn[] joinColumns() default {};" +
                " JoinColumn[] inverseJoinColumns() default {};" +
                " }"
        )
        fixture.addClass("package jakarta.persistence; public @interface Lob { }")
        fixture.addClass(
            "package jakarta.persistence; public @interface SequenceGenerator {" +
                " String name();" +
                " String sequenceName() default \"\";" +
                " int initialValue() default 1;" +
                " int allocationSize() default 50;" +
                " }"
        )

        // 관계 어노테이션
        fixture.addClass(
            "package jakarta.persistence; public @interface ManyToOne {" +
                " Class<?> targetEntity() default void.class;" +
                " FetchType fetch() default FetchType.EAGER;" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; public @interface OneToOne {" +
                " Class<?> targetEntity() default void.class;" +
                " String mappedBy() default \"\";" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; public @interface OneToMany {" +
                " Class<?> targetEntity() default void.class;" +
                " String mappedBy() default \"\";" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; public @interface ManyToMany {" +
                " Class<?> targetEntity() default void.class;" +
                " String mappedBy() default \"\";" +
                " }"
        )

        // @Enumerated / @Temporal / @Embedded / @Version / @ElementCollection — DDL 매핑 영향 속성
        fixture.addClass("package jakarta.persistence; public enum EnumType { ORDINAL, STRING }")
        fixture.addClass("package jakarta.persistence; public @interface Enumerated { EnumType value() default EnumType.ORDINAL; }")
        fixture.addClass("package jakarta.persistence; public enum TemporalType { DATE, TIME, TIMESTAMP }")
        fixture.addClass("package jakarta.persistence; public @interface Temporal { TemporalType value(); }")
        fixture.addClass("package jakarta.persistence; public @interface Embedded { }")
        fixture.addClass("package jakarta.persistence; public @interface EmbeddedId { }")
        fixture.addClass("package jakarta.persistence; public @interface IdClass { Class<?> value(); }")
        fixture.addClass("package jakarta.persistence; public @interface ElementCollection { }")
        fixture.addClass("package jakarta.persistence; public @interface Version { }")
        fixture.addClass("package jakarta.persistence; public @interface Basic { }")
    }
}
