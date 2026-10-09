package com.fixgo.shared.storage;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Key naming and validation. A key is only ever built here, or checked here before it reaches a storage. */
public final class StorageKeys {
    private static final String EXT = "(jpg|png|webp|heic)";
    private static final Pattern PHOTO = Pattern.compile("photos/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\." + EXT);
    private static final Pattern KYC = Pattern.compile("kyc/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\." + EXT);

    private StorageKeys() { }

    public static String extensionFor(String mime) {
        return switch (mime) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic" -> "heic";
            default -> "jpg";
        };
    }

    public static String photoKey(String mime) { return "photos/" + UUID.randomUUID() + "." + extensionFor(mime); }

    public static String kycKey(UUID ownerId, String mime) { return "kyc/" + ownerId + "/" + UUID.randomUUID() + "." + extensionFor(mime); }

    public static boolean isPhotoKey(String key) { return key != null && PHOTO.matcher(key).matches(); }

    /** True when {@code key} is a KYC key issued to {@code ownerId}. */
    public static boolean isKycKeyOf(UUID ownerId, String key) {
        if (key == null) return false;
        var m = KYC.matcher(key);
        return m.matches() && m.group(1).equals(ownerId.toString().toLowerCase(Locale.ROOT));
    }

    public static boolean isKycKey(String key) { return key != null && KYC.matcher(key).matches(); }

    public static String contentTypeOf(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.endsWith(".png")) return "image/png";
        if (k.endsWith(".webp")) return "image/webp";
        if (k.endsWith(".heic")) return "image/heic";
        return "image/jpeg";
    }
}
