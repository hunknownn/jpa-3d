package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 연관관계 없는 독립 테이블 예제. 감사 로그.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor", nullable = false, length = 120)
    private String actor;

    @Column(name = "action", nullable = false, length = 80)
    private String action;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
