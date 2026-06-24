package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연관관계 없는 독립 테이블 예제. 레이트 리밋 규칙.
 */
@Entity
@Table(name = "rate_limit_rules")
public class RateLimitRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pattern", nullable = false, length = 200)
    private String pattern;

    @Column(name = "limit_per_minute", nullable = false)
    private int limitPerMinute;

    public Long getId() {
        return id;
    }

    public String getPattern() {
        return pattern;
    }

    public int getLimitPerMinute() {
        return limitPerMinute;
    }
}
