package com.example.shop.repository;

import com.example.shop.catalog.Category;
import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends BaseRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    List<Category> findByParentIsNull();
}
