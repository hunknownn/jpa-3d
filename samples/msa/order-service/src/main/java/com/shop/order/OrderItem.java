package com.shop.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 같은 서비스 내부의 진짜 FK — INTRA.
    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // 다른 서비스(product-service)의 Product 를 가리키는 약한 ID 참조 — CROSS_SOFT.
    private Long productId;

    private int quantity;
}
