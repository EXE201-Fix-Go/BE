package com.fixgo.module.pricing.controller;

import com.fixgo.module.pricing.entity.CallOutFeeConfig;
import com.fixgo.module.pricing.entity.TravelFeeConfig;
import com.fixgo.module.pricing.repository.CallOutFeeConfigRepository;
import com.fixgo.module.pricing.repository.TravelFeeConfigRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;

/** Public read of the rates currently in force (RB-23), so clients never hardcode a fee. */
@RestController
@RequestMapping("/api/v1/pricing")
public class PricingController {
    private final CallOutFeeConfigRepository callOutFees;
    private final TravelFeeConfigRepository travelFees;
    private final Clock clock;

    public PricingController(CallOutFeeConfigRepository callOutFees, TravelFeeConfigRepository travelFees, Clock clock) {
        this.callOutFees = callOutFees;
        this.travelFees = travelFees;
        this.clock = clock;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PricingResponse current() {
        var now = clock.instant();
        var callOut = callOutFees.findActiveGlobal(now).map(CallOutFeeConfig::getFeeAmount).orElse(null);
        var travel = travelFees.findActiveGlobal(now).orElse(null);
        return new PricingResponse(callOut,
                travel == null ? null : travel.getPerKmAmount(),
                travel == null ? null : travel.getFreeKm());
    }

    /** A null field means no rate is configured; clients show nothing rather than guess. */
    public record PricingResponse(BigDecimal callOutFee, BigDecimal travelPerKm, BigDecimal travelFreeKm) { }
}
