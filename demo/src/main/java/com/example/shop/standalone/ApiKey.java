package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연관관계 없는 독립 테이블 예제. 외부 API 키.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_value", nullable = false, unique = true, length = 120)
    private String keyValue;

    @Column(name = "owner", nullable = false, length = 120)
    private String owner;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    public Long getId() {
        return id;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public String getOwner() {
        return owner;
    }

    public boolean isRevoked() {
        return revoked;
    }
}
