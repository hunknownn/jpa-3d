package com.shop.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class ProductReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // product-service 내부 — INTRA.
    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    // 다른 서비스(user-service)의 User 를 가리키는 약한 ID 참조 — CROSS_SOFT(점선).
    private Long userId;

    private int rating;

    @Column(length = 1000)
    private String comment;
}
