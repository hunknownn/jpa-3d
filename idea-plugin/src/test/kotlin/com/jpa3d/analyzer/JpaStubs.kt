package com.jpa3d.analyzer

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture

/**
 * 테스트 모듈에 `jakarta.persistence` 스텁 어노테이션/enum 을 인메모리로 주입한다.
 *
 * 진짜 jakarta.persistence-api jar 를 의존성으로 끌어오면 부트 비용이 늘고 버전 의존이 생긴다.
 * 스텁만으로도 analyzer 가 PSI 의 qualifiedName 매칭으로 동작하기엔 충분.
 *
 * 단, 필드 레벨 어노테이션은 실제 jakarta 처럼 `@Target({METHOD, FIELD})` 를 반드시 부여한다.
 * Kotlin 주생성자 프로퍼티(`class User(@Id val id: Long)`)에 어노테이션이 붙을 때, target 이
 * 없으면 Kotlin 은 "어디든 적용 가능" 으로 보고 **파라미터** 에 붙여(param→property→field 우선순위)
 * `UClass.fields` 의 field 에서 어노테이션이 사라진다. 실제 jakarta 는 PARAMETER target 이 없어
 * field 로 떨어지므로, 스텁도 동일하게 맞춰야 테스트가 현실을 반영한다.
 */
internal object JpaStubs {

    /** 필드/메서드 레벨 어노테이션용 @Target — Kotlin 주생성자 프로퍼티에서 field 로 떨어지게 한다. */
    private const val ON_FIELD =
        "@java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.FIELD}) "

    /** 타입(클래스) 레벨 어노테이션용 @Target. */
    private const val ON_TYPE =
        "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE) "

    fun install(fixture: JavaCodeInsightTestFixture) {
        // enum
        fixture.addClass("package jakarta.persistence; public enum GenerationType { TABLE, SEQUENCE, IDENTITY, AUTO, UUID }")
        fixture.addClass("package jakarta.persistence; public enum InheritanceType { SINGLE_TABLE, JOINED, TABLE_PER_CLASS }")
        fixture.addClass("package jakarta.persistence; public enum DiscriminatorType { STRING, CHAR, INTEGER }")
        fixture.addClass("package jakarta.persistence; public enum FetchType { LAZY, EAGER }")

        // 클래스 레벨 어노테이션
        fixture.addClass("package jakarta.persistence; ${ON_TYPE}public @interface Entity { String name() default \"\"; }")
        fixture.addClass("package jakarta.persistence; ${ON_TYPE}public @interface MappedSuperclass { }")
        fixture.addClass("package jakarta.persistence; ${ON_TYPE}public @interface Embeddable { }")
        fixture.addClass(
            "package jakarta.persistence; ${ON_TYPE}public @interface Table {" +
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
            "package jakarta.persistence; ${ON_TYPE}public @interface Inheritance {" +
                " InheritanceType strategy() default InheritanceType.SINGLE_TABLE;" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; ${ON_TYPE}public @interface DiscriminatorColumn {" +
                " String name() default \"DTYPE\";" +
                " DiscriminatorType discriminatorType() default DiscriminatorType.STRING;" +
                " int length() default 31;" +
                " }"
        )
        fixture.addClass("package jakarta.persistence; ${ON_TYPE}public @interface DiscriminatorValue { String value(); }")
        fixture.addClass("package jakarta.persistence; ${ON_TYPE}public @interface IdClass { Class<?> value(); }")

        // 필드 레벨
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface Id { }")
        fixture.addClass(
            "package jakarta.persistence; ${ON_FIELD}public @interface GeneratedValue {" +
                " GenerationType strategy() default GenerationType.AUTO;" +
                " String generator() default \"\";" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; ${ON_FIELD}public @interface Column {" +
                " String name() default \"\";" +
                " boolean nullable() default true;" +
                " boolean unique() default false;" +
                " int length() default 255;" +
                " int precision() default 0;" +
                " int scale() default 0;" +
                " String columnDefinition() default \"\";" +
                " }"
        )
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface Transient { }")
        fixture.addClass(
            "package jakarta.persistence; ${ON_FIELD}public @interface JoinColumn {" +
                " String name() default \"\";" +
                " boolean nullable() default true;" +
                " boolean unique() default false;" +
                " String referencedColumnName() default \"\";" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; ${ON_FIELD}public @interface JoinTable {" +
                " String name() default \"\";" +
                " JoinColumn[] joinColumns() default {};" +
                " JoinColumn[] inverseJoinColumns() default {};" +
                " }"
        )
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface Lob { }")
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
            "package jakarta.persistence; ${ON_FIELD}public @interface ManyToOne {" +
                " Class<?> targetEntity() default void.class;" +
                " FetchType fetch() default FetchType.EAGER;" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; ${ON_FIELD}public @interface OneToOne {" +
                " Class<?> targetEntity() default void.class;" +
                " String mappedBy() default \"\";" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; ${ON_FIELD}public @interface OneToMany {" +
                " Class<?> targetEntity() default void.class;" +
                " String mappedBy() default \"\";" +
                " }"
        )
        fixture.addClass(
            "package jakarta.persistence; ${ON_FIELD}public @interface ManyToMany {" +
                " Class<?> targetEntity() default void.class;" +
                " String mappedBy() default \"\";" +
                " }"
        )

        // @Enumerated / @Temporal / @Embedded / @Version / @ElementCollection — DDL 매핑 영향 속성
        fixture.addClass("package jakarta.persistence; public enum EnumType { ORDINAL, STRING }")
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface Enumerated { EnumType value() default EnumType.ORDINAL; }")
        fixture.addClass("package jakarta.persistence; public enum TemporalType { DATE, TIME, TIMESTAMP }")
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface Temporal { TemporalType value(); }")
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface Embedded { }")
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface EmbeddedId { }")
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface ElementCollection { }")
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface Version { }")
        fixture.addClass("package jakarta.persistence; ${ON_FIELD}public @interface Basic { }")
    }
}
