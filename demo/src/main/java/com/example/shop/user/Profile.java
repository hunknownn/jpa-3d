package com.example.shop.user;

import com.example.shop.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "profiles")
public class Profile extends BaseEntity {

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Column(name = "bio", length = 1000)
    private String bio;

    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }
}
