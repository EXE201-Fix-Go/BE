package com.fixgo.user;

import com.fixgo.partner.PartnerType;

/**
 * Role code the mobile app switches navigation on. Derived from app_users.role plus
 * partner_profiles.partner_type; never stored.
 */
public enum AppRole {
    CUSTOMER, P_IND, P_SHOP, P_STAFF, ADMIN;

    public static AppRole of(Role role, PartnerType partnerType) {
        return switch (role) {
            case CUSTOMER -> CUSTOMER;
            case ADMIN -> ADMIN;
            case PARTNER -> partnerType == null ? P_IND : switch (partnerType) {
                case INDIVIDUAL -> P_IND;
                case SHOP -> P_SHOP;
                case SHOP_STAFF -> P_STAFF;
            };
        };
    }
}
