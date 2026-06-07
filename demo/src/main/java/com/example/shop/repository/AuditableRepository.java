package com.example.shop.repository;

import com.example.shop.common.AuditableEntity;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * 중간 리포지토리 2단계. BaseRepository 를 다시 확장해 다단계 상속 체인을 만든다
 * (concrete → AuditableRepository → BaseRepository → JpaRepository).
 */
@NoRepositoryBean
public interface AuditableRepository<T extends AuditableEntity, ID extends Serializable>
        extends BaseRepository<T, ID> {

    List<T> findByCreatedAtAfter(Instant since);
}
