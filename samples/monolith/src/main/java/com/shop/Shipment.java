package com.shop;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import java.time.LocalDateTime;

@Entity
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 주문 배송 1:1 — INTRA.
    @OneToOne
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    // 배송지 — 모놀리스라 진짜 FK. INTRA.
    @ManyToOne
    @JoinColumn(name = "address_id", nullable = false)
    private Address address;

    private String carrier;

    private String trackingNumber;

    private LocalDateTime shippedAt;
}
