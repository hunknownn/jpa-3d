package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연관관계 없는 독립 테이블 예제. SMS 템플릿.
 */
@Entity
@Table(name = "sms_templates")
public class SmsTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_code", nullable = false, unique = true, length = 80)
    private String templateCode;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    public Long getId() {
        return id;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public String getBody() {
        return body;
    }
}
