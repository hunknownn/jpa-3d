package com.shop.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다른 서비스(user-service)의 User 를 가리키지만 진짜 FK 가 아니라 약한 ID 참조 — CROSS_SOFT(점선).
    private Long userId;

    @OneToMany(mappedBy = "order")
    private List<OrderItem> items = new ArrayList<>();
}
