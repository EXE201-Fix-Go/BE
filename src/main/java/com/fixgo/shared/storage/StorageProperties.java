package com.fixgo.shared.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code provider}: {@code local} keeps files on this machine's disk (development only: Render's disk is wiped on
 * every deploy), {@code supabase} uses Supabase Storage with the project's service key (backend only).
 */
@ConfigurationProperties("fixgo.storage")
public record StorageProperties(String provider, String privateDir, Supabase supabase) {

    public StorageProperties {
        provider = provider == null || provider.isBlank() ? "local" : provider.strip().toLowerCase();
        privateDir = privateDir == null || privateDir.isBlank() ? ".local/private-uploads" : privateDir;
        supabase = supabase == null ? new Supabase(null, null, null, null, null) : supabase;
    }

    public boolean isLocal() { return "local".equals(provider); }

    public record Supabase(String url, String serviceKey, String publicBucket, String privateBucket, Duration timeout) {
        public Supabase {
            url = url == null ? "" : url.strip().replaceAll("/+$", "");
            publicBucket = publicBucket == null || publicBucket.isBlank() ? "fixgo-public" : publicBucket.strip();
            privateBucket = privateBucket == null || privateBucket.isBlank() ? "fixgo-kyc" : privateBucket.strip();
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }
}
