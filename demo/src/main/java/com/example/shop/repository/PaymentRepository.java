package com.example.shop.repository;

import com.example.shop.payment.Payment;
import java.util.Optional;

public interface PaymentRepository extends BaseRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);
}
