package com.example.shop.repository;

import com.example.shop.order.Order;
import com.example.shop.order.OrderStatus;
import java.util.List;

public interface OrderRepository extends AuditableRepository<Order, Long> {

    List<Order> findByCustomerId(Long customerId);

    List<Order> findByStatus(OrderStatus status);
}
