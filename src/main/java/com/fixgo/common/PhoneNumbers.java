package com.fixgo.common;

import org.springframework.http.HttpStatus;
import java.util.regex.Pattern;

/** Normalises Vietnamese mobile numbers to E.164 (RB-02: identities are stored as +84…). */
public final class PhoneNumbers {
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    private PhoneNumbers() { }

    public static String toE164(String raw) {
        if (raw == null) throw invalid();
        String digits = raw.replaceAll("[\\s.\\-()]", "");
        if (digits.startsWith("00")) digits = "+" + digits.substring(2);
        if (digits.startsWith("0") && digits.length() == 10) digits = "+84" + digits.substring(1);
        else if (digits.startsWith("84") && digits.length() == 11) digits = "+" + digits;
        if (!E164.matcher(digits).matches()) throw invalid();
        return digits;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE",
                "Use a Vietnamese mobile number such as 0901234567 or +84901234567.");
    }
}
