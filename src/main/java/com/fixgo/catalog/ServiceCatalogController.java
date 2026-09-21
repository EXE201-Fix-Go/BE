package com.fixgo.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceCatalogController {
    private final ServiceCatalogRepository services;

    public ServiceCatalogController(ServiceCatalogRepository services) { this.services = services; }

    @GetMapping
    public List<ServiceResponse> list() {
        return services.findByActiveTrueOrderBySortOrderAsc().stream().map(ServiceResponse::from).toList();
    }

    public record ServiceResponse(String id, String name, String description, BigDecimal price) {
        static ServiceResponse from(ServiceCatalog s) {
            return new ServiceResponse(s.getCode(), s.getName(), s.getDescription(), s.getBasePrice());
        }
    }
}
