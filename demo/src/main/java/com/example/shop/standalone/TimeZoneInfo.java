package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연관관계 없는 독립 테이블 예제. 타임존 정보.
 */
@Entity
@Table(name = "time_zones")
public class TimeZoneInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false, unique = true, length = 60)
    private String zoneId;

    @Column(name = "offset_minutes", nullable = false)
    private int offsetMinutes;

    public Long getId() {
        return id;
    }

    public String getZoneId() {
        return zoneId;
    }

    public int getOffsetMinutes() {
        return offsetMinutes;
    }
}
