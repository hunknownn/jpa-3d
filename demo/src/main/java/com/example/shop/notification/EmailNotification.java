package com.example.shop.notification;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("EMAIL")
public class EmailNotification extends Notification {

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "from_address", length = 120)
    private String fromAddress;

    public String getSubject() {
        return subject;
    }

    public String getFromAddress() {
        return fromAddress;
    }
}
