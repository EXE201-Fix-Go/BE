package com.fixgo.module.order.repository;
import com.fixgo.module.order.entity.*;
import com.fixgo.module.order.enums.*;
import com.fixgo.module.order.dto.*;
import com.fixgo.module.order.repository.*;
import com.fixgo.module.order.service.*;


import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {
    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAsc(UUID orderId);
}
