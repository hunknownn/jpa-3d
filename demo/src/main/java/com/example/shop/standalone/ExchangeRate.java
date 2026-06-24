package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * 연관관계 없는 독립 테이블 예제. 환율 스냅샷.
 */
@Entity
@Table(name = "exchange_rates")
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "base_code", nullable = false, length = 3)
    private String baseCode;

    @Column(name = "quote_code", nullable = false, length = 3)
    private String quoteCode;

    @Column(name = "rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    public Long getId() {
        return id;
    }

    public String getBaseCode() {
        return baseCode;
    }

    public String getQuoteCode() {
        return quoteCode;
    }

    public BigDecimal getRate() {
        return rate;
    }
}
