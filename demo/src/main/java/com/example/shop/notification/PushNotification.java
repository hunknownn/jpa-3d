package com.example.shop.notification;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("PUSH")
public class PushNotification extends Notification {

    @Column(name = "device_token", length = 255)
    private String deviceToken;

    @Column(name = "platform", length = 20)
    private String platform;

    public String getDeviceToken() {
        return deviceToken;
    }

    public String getPlatform() {
        return platform;
    }
}
