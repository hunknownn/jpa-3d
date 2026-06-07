package com.example.shop.repository;

import com.example.shop.catalog.Product;
import java.util.List;

public interface ProductRepository extends AuditableRepository<Product, Long> {

    List<Product> findByCategoryId(Long categoryId);

    List<Product> findByStockQuantityGreaterThan(int threshold);
}
