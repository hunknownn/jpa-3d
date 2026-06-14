package com.shop.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * MSA 패턴 — 다른 서비스(user-service)의 데이터를 조인 대신 **비정규화 복제**로 들고 있는다.
 * userId 는 원본 User 를 가리키는 약한 ID 참조(CROSS_SOFT).
 */
@Entity
@Table(name = "user_snapshots")
public class UserSnapshot {

    @Id
    private Long id;

    private Long userId;

    @Column(nullable = false)
    private String userEmail;

    private String userName;
}
