package com.shop.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다른 서비스(user-service)의 User 를 가리키는 약한 ID 참조 — CROSS_SOFT.
    private Long userId;

    @OneToMany(mappedBy = "cart")
    private List<CartItem> items = new ArrayList<>();

    private LocalDateTime createdAt;
}
