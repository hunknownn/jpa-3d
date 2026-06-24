package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 연관관계 없는 독립 테이블 예제. 시스템 메트릭 스냅샷.
 */
@Entity
@Table(name = "system_metrics")
public class SystemMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_name", nullable = false, length = 80)
    private String metricName;

    @Column(name = "metric_value", nullable = false)
    private double metricValue;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    public Long getId() {
        return id;
    }

    public String getMetricName() {
        return metricName;
    }

    public double getMetricValue() {
        return metricValue;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
