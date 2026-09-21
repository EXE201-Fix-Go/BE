package com.fixgo.quote;

import com.fixgo.catalog.ServiceCatalogRepository;
import com.fixgo.common.Actor;
import com.fixgo.common.ApiException;
import com.fixgo.order.*;
import com.fixgo.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Quotes are versioned, never edited (C-02 / RB-44); only the order's customer decides (RB-45). */
@Service
public class QuoteService {
    private final QuoteRepository quotes;
    private final RescueOrderRepository orders;
    private final ServiceCatalogRepository catalog;
    private final OrderService orderService;
    private final OrderStateMachine stateMachine;
    private final QuoteMapper mapper;
    private final Clock clock;

    public QuoteService(QuoteRepository quotes, RescueOrderRepository orders, ServiceCatalogRepository catalog,
                        OrderService orderService, OrderStateMachine stateMachine, QuoteMapper mapper, Clock clock) {
        this.quotes = quotes;
        this.orders = orders;
        this.catalog = catalog;
        this.orderService = orderService;
        this.stateMachine = stateMachine;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * GW-01 → GW-02: from CHECKING this is the INITIAL quote; from IN_PROGRESS it is an ADDITIONAL revision (BR03).
     * RB-46: only one SENT quote per order at a time.
     */
    @Transactional
    public QuoteDtos.QuoteResponse send(Actor actor, UUID orderId, QuoteDtos.CreateQuoteRequest request) {
        var order = orderService.lockAssigned(actor, orderId);
        if (quotes.findFirstByOrderIdAndStatus(orderId, Quote.Status.SENT).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "QUOTE_ALREADY_SENT", "A quote is already awaiting the customer.");
        }
        Quote.Type type = switch (order.getStatus()) {
            case CHECKING -> Quote.Type.INITIAL;
            case IN_PROGRESS -> Quote.Type.ADDITIONAL;
            default -> throw OrderStateMachine.wrongState(order.getStatus(), "quote");
        };
        var now = clock.instant();
        int revision = quotes.findFirstByOrderIdOrderByRevisionNoDesc(orderId).map(q -> q.getRevisionNo() + 1).orElse(1);
        var quote = new Quote(orderId, actor.userId(), revision, type, order.getCallOutFeeSnapshot(), now,
                request.validMinutes() == null ? null : now.plus(Duration.ofMinutes(request.validMinutes())));
        for (var item : request.items()) {
            UUID serviceId = item.serviceId() == null ? null
                    : catalog.findByCodeAndActiveTrue(item.serviceId()).map(s -> s.getId()).orElse(null);
            quote.addItem(item.itemType(), item.description().strip(), item.quantity(), item.unitPrice(), serviceId);
        }
        if (quote.getTotalAmount().signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NEGATIVE_TOTAL", "Discounts exceed the quote total.");
        }
        quote.send(now);
        quotes.save(quote);
        stateMachine.transition(order, type == Quote.Type.INITIAL ? OrderStatus.WAITING_FOR_APPROVAL
                : OrderStatus.ADDITIONAL_QUOTE, actor.userId(), ActorType.PARTNER, "Quote revision " + revision + " sent");
        return mapper.toResponse(quote);
    }

    /** BR02: work only starts after approval. Previous APPROVED revisions become SUPERSEDED (RB-44). */
    @Transactional
    public QuoteDtos.QuoteResponse approve(Actor actor, UUID orderId, UUID quoteId) {
        var order = lockAsCustomer(actor, orderId);
        var quote = lockSent(orderId, quoteId);
        var now = clock.instant();
        quotes.findFirstByOrderIdAndStatusOrderByRevisionNoDesc(orderId, Quote.Status.APPROVED).ifPresent(Quote::supersede);
        quote.approve(actor.userId(), now);
        if (order.getStatus() == OrderStatus.WAITING_FOR_APPROVAL) {
            stateMachine.transition(order, OrderStatus.APPROVED, actor.userId(), ActorType.CUSTOMER, "Quote approved");
            stateMachine.transition(order, OrderStatus.IN_PROGRESS, actor.userId(), ActorType.CUSTOMER, null);
        } else if (order.getStatus() == OrderStatus.ADDITIONAL_QUOTE) {
            stateMachine.transition(order, OrderStatus.IN_PROGRESS, actor.userId(), ActorType.CUSTOMER, "Additional quote approved");
        } else {
            throw OrderStateMachine.wrongState(order.getStatus(), "approve a quote for");
        }
        return mapper.toResponse(quote);
    }

    /**
     * GW-02 "No": an initial quote refused cancels the order (fee still due if the partner had arrived);
     * an additional quote refused just returns to the already-approved scope.
     */
    @Transactional
    public QuoteDtos.QuoteResponse decline(Actor actor, UUID orderId, UUID quoteId, String reason) {
        var order = lockAsCustomer(actor, orderId);
        var quote = lockSent(orderId, quoteId);
        quote.decline(actor.userId(), reason, clock.instant());
        if (order.getStatus() == OrderStatus.WAITING_FOR_APPROVAL) {
            orderService.cancelLocked(order, actor, "QUOTE_DECLINED" + (reason == null ? "" : ": " + reason));
        } else if (order.getStatus() == OrderStatus.ADDITIONAL_QUOTE) {
            stateMachine.transition(order, OrderStatus.IN_PROGRESS, actor.userId(), ActorType.CUSTOMER, "Additional quote declined");
        } else {
            throw OrderStateMachine.wrongState(order.getStatus(), "decline a quote for");
        }
        return mapper.toResponse(quote);
    }

    @Transactional(readOnly = true)
    public List<QuoteDtos.QuoteResponse> list(Actor actor, UUID orderId) {
        orderService.get(actor, orderId);   // visibility check
        return quotes.findByOrderIdOrderByRevisionNoAsc(orderId).stream().map(mapper::toResponse).toList();
    }

    /** RB-45: only the customer of the order; ADMIN is explicitly refused. */
    private RescueOrder lockAsCustomer(Actor actor, UUID orderId) {
        var order = orders.lockById(orderId).orElseThrow(OrderService::notFound);
        if (!actor.is(Role.CUSTOMER)) throw OrderService.forbidden();
        if (!order.getCustomerId().equals(actor.userId())) throw OrderService.notFound();
        return order;
    }

    private Quote lockSent(UUID orderId, UUID quoteId) {
        var quote = quotes.lockById(quoteId).orElseThrow(QuoteService::quoteNotFound);
        if (!quote.getOrderId().equals(orderId)) throw quoteNotFound();
        if (quote.getStatus() != Quote.Status.SENT) {
            throw new ApiException(HttpStatus.CONFLICT, "QUOTE_NOT_PENDING", "This quote is not awaiting a decision.");
        }
        return quote;
    }

    private static ApiException quoteNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND", "Quote does not exist.");
    }
}
