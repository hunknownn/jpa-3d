package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연관관계 없는 독립 테이블 예제. 에러 코드 카탈로그.
 */
@Entity
@Table(name = "error_catalog")
public class ErrorCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "message", nullable = false, length = 300)
    private String message;

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
