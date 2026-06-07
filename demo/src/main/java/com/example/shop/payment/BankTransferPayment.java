package com.example.shop.payment;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@DiscriminatorValue("BANK_TRANSFER")
@Table(name = "bank_transfer_payments")
public class BankTransferPayment extends Payment {

    @Column(name = "bank_name", length = 60)
    private String bankName;

    @Column(name = "account_number", length = 40)
    private String accountNumber;

    public String getBankName() {
        return bankName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }
}
