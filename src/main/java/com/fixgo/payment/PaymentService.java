package com.fixgo.payment;

import com.fixgo.common.Actor;
import com.fixgo.common.ApiException;
import com.fixgo.order.ActorType;
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
}
