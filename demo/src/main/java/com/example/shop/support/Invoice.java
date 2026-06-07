package com.example.shop.support;

import com.example.shop.common.Money;
import com.example.shop.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "invoices",
        uniqueConstraints = @UniqueConstraint(name = "uq_invoices_number", columnNames = "invoice_number")
)
public class Invoice extends Document {

    @Column(name = "invoice_number", nullable = false, unique = true, length = 30)
    private String invoiceNumber;

    @Embedded
    private Money total;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", unique = true, nullable = false)
    private Order order;

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public Money getTotal() {
        return total;
    }

    public Order getOrder() {
        return order;
    }
}
