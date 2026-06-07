package com.example.shop.repository;

import com.example.shop.review.Review;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 중간 인터페이스 없이 JpaRepository 를 직접 확장하는 단순 케이스(비교용).
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByProductId(Long productId);

    List<Review> findByAuthorId(Long authorId);
}
