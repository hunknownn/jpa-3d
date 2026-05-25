package com.jpa3d.analyzer

/**
 * JPA / Spring Data 어노테이션 FQN. jakarta / javax 양쪽을 모두 다룬다.
 *
 * PSI 의 `PsiAnnotation.qualifiedName` 으로 매칭한다. 단축 이름(@Entity)으로 비교하지 않는 이유는
 * 동명 사용자 정의 어노테이션과 충돌할 수 있어서.
 */
object JpaAnnotations {

    // 클래스 레벨
    val ENTITY = setOf("jakarta.persistence.Entity", "javax.persistence.Entity")
    val MAPPED_SUPERCLASS = setOf("jakarta.persistence.MappedSuperclass", "javax.persistence.MappedSuperclass")
    val EMBEDDABLE = setOf("jakarta.persistence.Embeddable", "javax.persistence.Embeddable")
    val TABLE = setOf("jakarta.persistence.Table", "javax.persistence.Table")
    val INHERITANCE = setOf("jakarta.persistence.Inheritance", "javax.persistence.Inheritance")
    val DISCRIMINATOR_COLUMN = setOf("jakarta.persistence.DiscriminatorColumn", "javax.persistence.DiscriminatorColumn")
    val DISCRIMINATOR_VALUE = setOf("jakarta.persistence.DiscriminatorValue", "javax.persistence.DiscriminatorValue")

    // 필드 레벨
    val ID = setOf("jakarta.persistence.Id", "javax.persistence.Id")
    val GENERATED_VALUE = setOf("jakarta.persistence.GeneratedValue", "javax.persistence.GeneratedValue")
    val COLUMN = setOf("jakarta.persistence.Column", "javax.persistence.Column")
    val TRANSIENT = setOf("jakarta.persistence.Transient", "javax.persistence.Transient")
    val JOIN_COLUMN = setOf("jakarta.persistence.JoinColumn", "javax.persistence.JoinColumn")
    val JOIN_TABLE = setOf("jakarta.persistence.JoinTable", "javax.persistence.JoinTable")

    // 관계
    val ONE_TO_MANY = setOf("jakarta.persistence.OneToMany", "javax.persistence.OneToMany")
    val MANY_TO_ONE = setOf("jakarta.persistence.ManyToOne", "javax.persistence.ManyToOne")
    val ONE_TO_ONE = setOf("jakarta.persistence.OneToOne", "javax.persistence.OneToOne")
    val MANY_TO_MANY = setOf("jakarta.persistence.ManyToMany", "javax.persistence.ManyToMany")

    val RELATION_ANNOTATIONS: Set<String> = ONE_TO_MANY + MANY_TO_ONE + ONE_TO_ONE + MANY_TO_MANY
    val ENTITY_KIND_ANNOTATIONS: Set<String> = ENTITY + MAPPED_SUPERCLASS + EMBEDDABLE
}

/**
 * Spring Data Repository 마커 인터페이스 FQN.
 * 직접 상속만 잡는다 (Repository<T, ID> 같은 일반 마커도 포함).
 */
object SpringDataRepositories {

    val MARKERS: Set<String> = setOf(
        "org.springframework.data.repository.Repository",
        "org.springframework.data.repository.CrudRepository",
        "org.springframework.data.repository.PagingAndSortingRepository",
        "org.springframework.data.repository.ListCrudRepository",
        "org.springframework.data.repository.ListPagingAndSortingRepository",
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.data.jpa.repository.JpaSpecificationExecutor",
        "org.springframework.data.mongodb.repository.MongoRepository",
        "org.springframework.data.r2dbc.repository.R2dbcRepository",
        "org.springframework.data.repository.reactive.ReactiveCrudRepository",
        "org.springframework.data.repository.reactive.ReactiveSortingRepository",
        "org.springframework.data.repository.reactive.RxJava3CrudRepository",
        "org.springframework.data.cassandra.repository.CassandraRepository",
        "org.springframework.data.couchbase.repository.CouchbaseRepository",
        "org.springframework.data.neo4j.repository.Neo4jRepository"
    )
}
