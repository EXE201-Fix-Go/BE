package com.fixgo.catalog;

import com.fixgo.module.catalog.entity.ServiceCatalog;
import com.fixgo.module.catalog.repository.ServiceCatalogRepository;
import com.fixgo.module.catalog.service.ServiceCatalogCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceCatalogCacheTest {
    private static ServiceCatalog service(String code, boolean active) {
        var s = mock(ServiceCatalog.class);
        when(s.getId()).thenReturn(UUID.randomUUID());
        when(s.getCode()).thenReturn(code);
        when(s.isActive()).thenReturn(active);
        when(s.getSortOrder()).thenReturn(1);
        return s;
    }

    private static ServiceCatalogCache cache(List<String> disabled) {
        var all = List.of(service("tire-patch", true), service("towing", true), service("old", false));
        var repository = mock(ServiceCatalogRepository.class);
        when(repository.findAll()).thenReturn(all);
        return new ServiceCatalogCache(repository, disabled);
    }

    @Test
    void aDisabledCodeIsHiddenEverywhere() {
        var cache = cache(List.of("towing"));
        assertThat(cache.active()).extracting(ServiceCatalog::getCode).containsExactly("tire-patch");
        assertThat(cache.activeByCode("towing")).isEmpty();
        assertThat(cache.activeByCodes(List.of("towing", "tire-patch"))).extracting(ServiceCatalog::getCode).containsExactly("tire-patch");
    }

    @Test
    void nothingIsHiddenWhenNoCodeIsDisabled() {
        var cache = cache(List.of());
        assertThat(cache.active()).extracting(ServiceCatalog::getCode).containsExactlyInAnyOrder("tire-patch", "towing");
        assertThat(cache.activeByCode("towing")).isPresent();
    }
}
