package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 연관관계 없는 독립 테이블 예제. 스케줄 작업 정의.
 */
@Entity
@Table(name = "scheduled_jobs")
public class ScheduledJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, unique = true, length = 100)
    private String jobName;

    @Column(name = "cron_expression", nullable = false, length = 80)
    private String cronExpression;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    public Long getId() {
        return id;
    }

    public String getJobName() {
        return jobName;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }
}
