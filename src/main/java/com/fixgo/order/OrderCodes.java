package com.fixgo.order;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Human-readable order codes such as FG-260921-7K3Q; uniqueness is enforced by the DB. */
final class OrderCodes {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.ofHours(7));
    private static final SecureRandom RANDOM = new SecureRandom();

    private OrderCodes() { }

    static String next(Instant now) {
        var sb = new StringBuilder("FG-").append(DAY.format(now)).append('-');
        for (int i = 0; i < 4; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
