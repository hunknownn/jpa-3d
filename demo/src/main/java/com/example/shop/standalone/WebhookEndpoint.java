package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연관관계 없는 독립 테이블 예제. 웹훅 엔드포인트.
 */
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "secret", length = 120)
    private String secret;

    @Column(name = "active", nullable = false)
    private boolean active;

    public Long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getSecret() {
        return secret;
    }

    public boolean isActive() {
        return active;
    }
}
