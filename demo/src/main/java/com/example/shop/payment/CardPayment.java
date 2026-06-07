package com.example.shop.payment;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@DiscriminatorValue("CARD")
@Table(name = "card_payments")
public class CardPayment extends Payment {

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    public String getCardLast4() {
        return cardLast4;
    }

    public String getCardBrand() {
        return cardBrand;
    }
}
