package com.shop.order;

import com.shop.catalog.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;

@Entity
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 같은 모듈(order) — INTRA.
    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // 모듈 경계를 넘는 진짜 FK — order → catalog. CROSS_FK.
    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    private int quantity;

    @Column(precision = 12, scale = 2)
    private BigDecimal unitPrice;
}
