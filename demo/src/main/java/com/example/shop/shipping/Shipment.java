package com.example.shop.shipping;

import com.example.shop.common.Address;
import com.example.shop.common.AuditableEntity;
import com.example.shop.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "shipments",
        uniqueConstraints = @UniqueConstraint(name = "uq_shipments_tracking", columnNames = "tracking_number")
)
public class Shipment extends AuditableEntity {

    @Column(name = "tracking_number", nullable = false, unique = true, length = 40)
    private String trackingNumber;

    @Column(name = "carrier", length = 40)
    private String carrier;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Embedded
    private Address destination;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", unique = true, nullable = false)
    private Order order;

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getCarrier() {
        return carrier;
    }

    public Order getOrder() {
        return order;
    }
}
