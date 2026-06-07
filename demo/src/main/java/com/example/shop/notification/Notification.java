package com.example.shop.notification;

import com.example.shop.common.BaseEntity;
import com.example.shop.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * SINGLE_TABLE 상속 전략 — 모든 하위 타입이 discriminator 컬럼으로 한 테이블에 저장된다.
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "channel", discriminatorType = DiscriminatorType.STRING)
@Table(name = "notifications")
public abstract class Notification extends BaseEntity {

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "message", length = 1000)
    private String message;

    @Column(name = "read_flag", nullable = false)
    private boolean read;

    @Column(name = "sent_at")
    private Instant sentAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRead() {
        return read;
    }

    public User getRecipient() {
        return recipient;
    }
}
