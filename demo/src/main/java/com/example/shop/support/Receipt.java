package com.example.shop.support;

import com.example.shop.payment.Payment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "receipts")
public class Receipt extends Document {

    @Column(name = "receipt_number", nullable = false, length = 30)
    private String receiptNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public Payment getPayment() {
        return payment;
    }
}
