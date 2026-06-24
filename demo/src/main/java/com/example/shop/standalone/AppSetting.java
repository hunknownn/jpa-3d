package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연관관계 없는 독립 테이블 예제. 애플리케이션 키-값 설정.
 */
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 80)
    private String configKey;

    @Column(name = "config_value", length = 500)
    private String configValue;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    public Long getId() {
        return id;
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
