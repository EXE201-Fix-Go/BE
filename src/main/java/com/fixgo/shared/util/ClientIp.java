package com.fixgo.shared.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client address for rate limiting. X-Forwarded-For is only trustworthy for the entries appended by our own
 * proxies: a client can prepend anything it likes, so the real address is the one {@code trustedProxyHops}
 * entries from the right. With 0 hops (local run, no proxy) the header is ignored entirely.
 */
public final class ClientIp {
    private ClientIp() { }

    public static String resolve(HttpServletRequest request, int trustedProxyHops) {
        return resolve(request.getHeader("X-Forwarded-For"), request.getRemoteAddr(), trustedProxyHops);
    }

    static String resolve(String forwardedFor, String remoteAddr, int trustedProxyHops) {
        if (trustedProxyHops <= 0 || forwardedFor == null || forwardedFor.isBlank()) return remoteAddr;
        String[] parts = forwardedFor.split(",");
        int index = parts.length - trustedProxyHops;
        if (index < 0) return remoteAddr;          // fewer hops than expected: the request did not come through the proxy
        String ip = parts[index].strip();
        return ip.isEmpty() ? remoteAddr : ip;
    }
}
