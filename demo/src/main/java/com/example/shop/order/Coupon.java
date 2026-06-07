package com.example.shop.order;

import com.example.shop.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "coupons",
        uniqueConstraints = @UniqueConstraint(name = "uq_coupons_code", columnNames = "code")
)
public class Coupon extends AuditableEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "discount_percent", nullable = false)
    private int discountPercent;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @ManyToMany(mappedBy = "coupons", fetch = FetchType.LAZY)
    private Set<Order> orders = new HashSet<>();

    public String getCode() {
        return code;
    }

    public int getDiscountPercent() {
        return discountPercent;
    }

    public Set<Order> getOrders() {
        return orders;
    }
}
