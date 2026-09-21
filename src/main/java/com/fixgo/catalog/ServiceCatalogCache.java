package com.fixgo.catalog;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory snapshot of the (tiny, rarely changing) service catalogue. Every request used to pay one
 * database round trip per lookup; with Supabase far away that dominated latency.
 */
@Component
public class ServiceCatalogCache {
    private static final Duration TTL = Duration.ofMinutes(5);
    private final ServiceCatalogRepository repository;
    private volatile Snapshot snapshot;

    public ServiceCatalogCache(ServiceCatalogRepository repository) { this.repository = repository; }

    private record Snapshot(List<ServiceCatalog> all, Map<UUID, ServiceCatalog> byId, Map<String, ServiceCatalog> byCode,
                            Instant loadedAt) { }

    private Snapshot current() {
        var s = snapshot;
        if (s == null || s.loadedAt().plus(TTL).isBefore(Instant.now())) {
            var all = repository.findAll();
            s = new Snapshot(all, all.stream().collect(Collectors.toMap(ServiceCatalog::getId, Function.identity())),
                    all.stream().collect(Collectors.toMap(ServiceCatalog::getCode, Function.identity())), Instant.now());
            snapshot = s;
        }
        return s;
    }

    public void invalidate() { snapshot = null; }

    public List<ServiceCatalog> active() {
        return current().all().stream().filter(ServiceCatalog::isActive)
                .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder())).toList();
    }

    public Optional<ServiceCatalog> byId(UUID id) { return Optional.ofNullable(current().byId().get(id)); }

    public Optional<ServiceCatalog> activeByCode(String code) {
        return Optional.ofNullable(current().byCode().get(code)).filter(ServiceCatalog::isActive);
    }

    public List<ServiceCatalog> activeByCodes(Collection<String> codes) {
        var map = current().byCode();
        return codes.stream().distinct().map(map::get).filter(s -> s != null && s.isActive()).toList();
    }

    public Map<UUID, ServiceCatalog> byIds(Collection<UUID> ids) {
        var map = current().byId();
        return ids.stream().distinct().filter(map::containsKey).collect(Collectors.toMap(Function.identity(), map::get));
    }
}
