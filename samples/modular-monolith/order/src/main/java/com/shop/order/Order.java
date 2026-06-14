package com.shop.order;

import com.shop.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 모듈 경계를 넘는 진짜 FK — order → user. CROSS_FK 로 강조된다.
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 같은 모듈(order) 쿠폰 — INTRA.
    @ManyToOne
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @OneToMany(mappedBy = "order")
    private List<OrderItem> items = new ArrayList<>();

    @Column(nullable = false)
    private String status;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalAmount;

    private LocalDateTime createdAt;
}
