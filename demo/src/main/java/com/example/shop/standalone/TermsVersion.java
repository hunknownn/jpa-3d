package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 연관관계 없는 독립 테이블 예제. 약관 버전.
 */
@Entity
@Table(name = "terms_versions")
public class TermsVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version", nullable = false, unique = true, length = 20)
    private String version;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    public Long getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getContent() {
        return content;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }
}
