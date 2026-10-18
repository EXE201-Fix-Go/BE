package com.fixgo.pricing;

import com.fixgo.module.pricing.service.TravelFeeCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TravelFeeCalculatorTest {
    private static final BigDecimal PER_KM = BigDecimal.valueOf(5000);

    private static int fee(double km, int freeKm) {
        return TravelFeeCalculator.compute(km, BigDecimal.valueOf(freeKm), PER_KM).fee().intValueExact();
    }

    @Test
    void chargesTheDistanceActuallyTravelledNotAWholeKilometre() {
        assertThat(fee(0.45, 0)).as("0.45 km rounds to 0.5 km -> 2,500 -> 3,000").isEqualTo(3000);
        assertThat(fee(0.4, 0)).as("0.4 km -> 2,000").isEqualTo(2000);
        assertThat(fee(1.5, 0)).as("1.5 km -> 7,500 -> 8,000").isEqualTo(8000);
        assertThat(fee(3.0, 0)).isEqualTo(15000);
    }

    @Test
    void nothingIsChargedInsideTheFreeDistanceOrAtTheSamePlace() {
        assertThat(fee(0.0, 0)).isZero();
        assertThat(fee(1.0, 1)).isZero();
        assertThat(fee(0.5, 1)).isZero();
    }

    @Test
    void onlyTheDistanceBeyondTheFreeAllowanceIsBilled() {
        assertThat(fee(3.0, 1)).isEqualTo(10000);
        assertThat(fee(1.4, 1)).as("0.4 km beyond the allowance").isEqualTo(2000);
    }

    @Test
    void theFeeCanBeRederivedFromTheDisplayedDistance() {
        var r = TravelFeeCalculator.compute(0.4449, BigDecimal.ZERO, PER_KM);
        assertThat(r.distanceKm()).isEqualByComparingTo("0.4");
        assertThat(r.fee()).isEqualByComparingTo("2000");
    }
}
