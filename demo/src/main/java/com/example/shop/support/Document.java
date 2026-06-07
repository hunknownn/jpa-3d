package com.example.shop.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import java.time.Instant;

/**
 * TABLE_PER_CLASS 상속 전략 — 하위 타입마다 독립 테이블을 가진다. 이 전략은
 * IDENTITY 생성을 쓸 수 없어 TABLE 전략을 명시한다(@MappedSuperclass 대신 자체 @Id).
 */
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public abstract class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE)
    private Long id;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "issued_at")
    private Instant issuedAt;

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
