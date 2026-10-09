package com.fixgo.module.pricing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Travel fee from the straight-line distance between the partner and the pickup point (RB-23).
 * Pro-rata: the customer pays for the distance actually travelled beyond the free allowance, rounded to the
 * nearest 1,000 VND. The distance is rounded to 0.1 km first, so the fee can be re-derived from what the
 * customer is shown (distance × rate).
 */
public final class TravelFeeCalculator {
    private static final BigDecimal VND_STEP = BigDecimal.valueOf(1000);

    private TravelFeeCalculator() { }

    public record Result(BigDecimal distanceKm, BigDecimal fee) { }

    public static Result compute(double straightLineKm, BigDecimal freeKm, BigDecimal perKmAmount) {
        BigDecimal distanceKm = BigDecimal.valueOf(straightLineKm).setScale(1, RoundingMode.HALF_UP);
        BigDecimal billableKm = distanceKm.subtract(freeKm).max(BigDecimal.ZERO);
        BigDecimal fee = billableKm.multiply(perKmAmount)
                .divide(VND_STEP, 0, RoundingMode.HALF_UP)
                .multiply(VND_STEP);
        return new Result(distanceKm, fee);
    }
}
