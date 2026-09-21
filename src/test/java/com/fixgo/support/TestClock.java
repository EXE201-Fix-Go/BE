package com.fixgo.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** System clock plus an adjustable offset, so tests can fast-forward dispatch deadlines and OTP expiry. */
public class TestClock extends Clock {
    private final Clock base = Clock.systemUTC();
    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration by) { offset = offset.plus(by); }
    public void reset() { offset = Duration.ZERO; }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return base.instant().plus(offset); }
}
