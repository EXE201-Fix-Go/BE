package com.fixgo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceCatalogRepository extends JpaRepository<ServiceCatalog, UUID> {
    Optional<ServiceCatalog> findByCodeAndActiveTrue(String code);
    List<ServiceCatalog> findByCodeInAndActiveTrue(Collection<String> codes);
    List<ServiceCatalog> findByActiveTrueOrderBySortOrderAsc();
}
