package com.fixgo.module.payment.service;
import com.fixgo.module.payment.entity.*;
import com.fixgo.module.payment.repository.*;
import com.fixgo.module.payment.service.*;


import com.fixgo.shared.util.Actor;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.module.order.enums.ActorType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {
    private final PaymentRepository payments;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    /** Idempotent: a second call with the same key is a no-op (e.g. complete retried after a timeout). */
    @Transactional
    public void createPending(UUID orderId, UUID quoteId, UUID partnerId, BigDecimal amount, String idempotencyKey) {
        if (payments.existsByIdempotencyKey(idempotencyKey)) return;
        payments.save(new Payment(orderId, quoteId, partnerId, amount, idempotencyKey, clock.instant()));
    }

    /** "Đã thanh toán": the customer or the collecting partner confirms the cash hand-over (RB-59). */
    @Transactional
    public Payment confirm(Actor actor, UUID orderId) {
        var payment = payments.lockByOrderIdAndStatus(orderId, Payment.Status.PENDING)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                        "There is no pending payment for this order."));
        payment.confirm(actor.userId(), ActorType.of(actor.role()), clock.instant());
        return payment;
    }

    /** Same as confirm but a no-op when nothing is pending (idempotent completion). */
    @Transactional
    public void confirmIfPending(Actor actor, UUID orderId) {
        payments.lockByOrderIdAndStatus(orderId, Payment.Status.PENDING)
                .ifPresent(p -> p.confirm(actor.userId(), ActorType.of(actor.role()), clock.instant()));
    }

    @Transactional(readOnly = true)
    public Optional<Payment> latest(UUID orderId) {
        return payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId);
    }

    /**
     * Phí kiểm tra còn PENDING trên các đơn đã hủy (khách từ chối báo giá): thợ vẫn phải xác nhận đã thu
     * thì đơn mới thôi hiện trên Bảng tin và khoản tiền mới vào thu nhập.
     */
    @Transactional(readOnly = true)
    public java.util.List<com.fixgo.module.partner.dto.PartnerDtos.PendingCollection> listPendingCollections(Actor partner) {
        return payments.findPendingCollections(partner.userId(), Payment.Status.PENDING,
                        com.fixgo.module.order.enums.OrderStatus.CANCELLED).stream()
                .map(r -> new com.fixgo.module.partner.dto.PartnerDtos.PendingCollection(
                        (UUID) r[0], (String) r[1], (BigDecimal) r[2], (String) r[3]))
                .toList();
    }
}
