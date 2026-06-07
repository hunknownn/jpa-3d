package com.example.shop.repository;

import java.io.Serializable;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * 사용자 정의 중간 리포지토리 1단계. JpaRepository 를 직접 확장한다.
 * 플러그인의 "다단계 Spring Data 리포지토리 상속" 추적 대상.
 */
@NoRepositoryBean
public interface BaseRepository<T, ID extends Serializable> extends JpaRepository<T, ID> {

    Optional<T> findFirstByOrderByIdDesc();
}
