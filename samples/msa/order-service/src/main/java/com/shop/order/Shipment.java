package com.shop.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import java.time.LocalDateTime;

@Entity
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 같은 서비스(order) 내부 1:1 — INTRA.
    @OneToOne
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    // 다른 서비스(user-service)의 Address 를 가리키는 약한 ID 참조 — CROSS_SOFT.
    private Long addressId;

    private String carrier;

    private String trackingNumber;

    private LocalDateTime shippedAt;
}
