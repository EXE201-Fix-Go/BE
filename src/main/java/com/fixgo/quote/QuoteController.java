package com.fixgo.quote;

import com.fixgo.common.Actor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/quotes")
public class QuoteController {
    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) { this.quotes = quotes; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteDtos.QuoteResponse send(Authentication auth, @PathVariable UUID orderId,
                                        @Valid @RequestBody QuoteDtos.CreateQuoteRequest request) {
        return quotes.send(Actor.of(auth), orderId, request);
    }

    @GetMapping
    public List<QuoteDtos.QuoteResponse> list(Authentication auth, @PathVariable UUID orderId) {
        return quotes.list(Actor.of(auth), orderId);
    }

    @PostMapping("/{quoteId}/approve")
    public QuoteDtos.QuoteResponse approve(Authentication auth, @PathVariable UUID orderId, @PathVariable UUID quoteId) {
        return quotes.approve(Actor.of(auth), orderId, quoteId);
    }

    @PostMapping("/{quoteId}/decline")
    public QuoteDtos.QuoteResponse decline(Authentication auth, @PathVariable UUID orderId, @PathVariable UUID quoteId,
                                           @Valid @RequestBody(required = false) QuoteDtos.DeclineRequest request) {
        return quotes.decline(Actor.of(auth), orderId, quoteId, request == null ? null : request.reason());
    }
}
