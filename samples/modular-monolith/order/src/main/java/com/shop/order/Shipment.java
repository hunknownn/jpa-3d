package com.shop.order;

import com.shop.user.Address;
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

    // 같은 모듈(order) 1:1 — INTRA.
    @OneToOne
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    // 모듈 경계를 넘는 진짜 FK — order → user(Address). CROSS_FK.
    @ManyToOne
    @JoinColumn(name = "address_id", nullable = false)
    private Address address;

    private String carrier;

    private String trackingNumber;

    private LocalDateTime shippedAt;
}
