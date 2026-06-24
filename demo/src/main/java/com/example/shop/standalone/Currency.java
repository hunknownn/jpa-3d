package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연관관계 없는 독립 테이블 예제. 통화 코드 참조.
 */
@Entity
@Table(name = "currencies")
public class Currency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 3)
    private String code;

    @Column(name = "symbol", length = 4)
    private String symbol;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }
}
