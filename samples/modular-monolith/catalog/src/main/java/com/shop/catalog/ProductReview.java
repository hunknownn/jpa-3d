package com.shop.catalog;

import com.shop.user.User;
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

    // 같은 모듈(catalog) — INTRA.
    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    // 모듈 경계를 넘는 진짜 FK — catalog → user. CROSS_FK.
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private int rating;

    @Column(length = 1000)
    private String comment;
}
