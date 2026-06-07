package com.example.shop.payment;

import com.example.shop.common.AuditableEntity;
import com.example.shop.common.Money;
import com.example.shop.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JOINED 상속 전략 — 하위 타입(CardPayment, BankTransferPayment)이 별도 테이블로 조인된다.
 */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "payment_type", discriminatorType = DiscriminatorType.STRING)
@Table(name = "payments")
public abstract class Payment extends AuditableEntity {

    @Embedded
    private Money amount;

    @Column(name = "paid_at")
    private Instant paidAt;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", unique = true, nullable = false)
    private Order order;

    public Money getAmount() {
        return amount;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Order getOrder() {
        return order;
    }
}
