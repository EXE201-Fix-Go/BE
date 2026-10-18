package com.fixgo.shared.storage;

/** Identifies an image by its first bytes. The client-sent Content-Type is never trusted. */
public final class ImageSniffer {
    private ImageSniffer() { }

    /** @return image/jpeg, image/png, image/webp, image/heic, or null when the bytes are not one of those. */
    public static String detect(byte[] b) {
        if (b == null) return null;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            int boxSize = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
            int limit = Math.min(b.length, boxSize >= 12 && boxSize <= 128 ? boxSize : Math.min(b.length, 64));
            for (int offset = 8; offset + 4 <= limit; offset += 4) {
                if (offset == 12) continue; // minor_version (4 bytes)
                String brand = new String(b, offset, 4, java.nio.charset.StandardCharsets.US_ASCII).toLowerCase(java.util.Locale.ROOT);
                if (brand.equals("heic") || brand.equals("heix") || brand.equals("hevc")
                        || brand.equals("heis") || brand.equals("heim") || brand.equals("hevm") || brand.equals("hevs")
                        || brand.equals("mif1") || brand.equals("msf1") || brand.equals("mihb") || brand.equals("mihe")
                        || brand.equals("avif") || brand.equals("avis")) {
                    return "image/heic";
                }
            }
        }
        return null;
    }
}
