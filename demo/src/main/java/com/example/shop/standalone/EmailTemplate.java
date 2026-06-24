package com.example.shop.standalone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연관관계 없는 독립 테이블 예제. 이메일 템플릿.
 */
@Entity
@Table(name = "email_templates")
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_code", nullable = false, unique = true, length = 80)
    private String templateCode;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    public Long getId() {
        return id;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }
}
