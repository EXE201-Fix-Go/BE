package com.fixgo.module.partner.service;

import com.fixgo.module.dispatch.repository.OrderAssignmentRepository;
import com.fixgo.module.iam.enums.IdentityProvider;
import com.fixgo.module.iam.enums.Role;
import com.fixgo.module.iam.repository.UserIdentityRepository;
import com.fixgo.module.iam.repository.UserRepository;
import com.fixgo.module.iam.service.UserService;
import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.order.repository.RescueOrderRepository;
import com.fixgo.module.partner.dto.InvitationDtos.InvitationResponse;
import com.fixgo.module.partner.dto.PartnerDtos;
import com.fixgo.module.partner.entity.PartnerProfile;
import com.fixgo.module.partner.entity.PartnerServiceOffer;
import com.fixgo.module.partner.entity.ShopInvitation;
import com.fixgo.module.partner.enums.Availability;
import com.fixgo.module.partner.enums.PartnerType;
import com.fixgo.module.partner.repository.PartnerProfileRepository;
import com.fixgo.module.partner.repository.PartnerServiceOfferRepository;
import com.fixgo.module.partner.repository.ShopInvitationRepository;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.util.Actor;
import com.fixgo.shared.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Shop staff (AUTHZ §6.3). An owner can only invite a phone number; the person's account is left alone until they
 * accept, so nobody can be turned into a partner (and lose access to their own orders) by a stranger.
 */
@Service
public class ShopInvitationService {
    private static final Logger log = LoggerFactory.getLogger(ShopInvitationService.class);
    static final Duration TTL = Duration.ofDays(7);
    static final int MAX_PENDING_PER_SHOP = 50;

    private final ShopInvitationRepository invitations;
    private final PartnerProfileRepository profiles;
    private final PartnerServiceOfferRepository offers;
    private final OrderAssignmentRepository assignments;
    private final RescueOrderRepository orders;
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PartnerProfileService profileService;
    private final Clock clock;

    public ShopInvitationService(ShopInvitationRepository invitations, PartnerProfileRepository profiles,
                                 PartnerServiceOfferRepository offers, OrderAssignmentRepository assignments,
                                 RescueOrderRepository orders, UserRepository users, UserIdentityRepository identities,
                                 PartnerProfileService profileService, Clock clock) {
        this.invitations = invitations;
        this.profiles = profiles;
        this.offers = offers;
        this.assignments = assignments;
        this.orders = orders;
        this.users = users;
        this.identities = identities;
        this.profileService = profileService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ shop owner

    @Transactional
    public InvitationResponse invite(Actor actor, PartnerDtos.InviteStaffRequest request) {
        var shop = requireApprovedShop(actor);
        var now = clock.instant();
        String phone = PhoneNumbers.toE164(request.phone());
        if (phone.equals(identities.findPrimaryUid(shop.getUserId()).orElse(null))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "You cannot invite your own number.");
        }
        if (invitations.existsByShopIdAndInviteePhoneAndStatus(shop.getUserId(), phone, ShopInvitation.Status.PENDING)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_PENDING", "This number already has a pending invitation.");
        }
        if (invitations.countByShopIdAndStatus(shop.getUserId(), ShopInvitation.Status.PENDING) >= MAX_PENDING_PER_SHOP) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_INVITATIONS", "Too many pending invitations.");
        }
        var identity = identities.findByProviderAndProviderUid(IdentityProvider.PHONE, phone).orElse(null);
        if (identity != null) {
            var user = identity.getUser();
            if (user.getRole() == Role.ADMIN) throw forbidden();
            var existing = profiles.findById(user.getId()).orElse(null);
            if (existing != null && existing.getPartnerType() != PartnerType.INDIVIDUAL) {
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PARTNER", "This phone already belongs to a shop.");
            }
        }
        var saved = invitations.save(new ShopInvitation(shop.getUserId(), phone, request.fullName().strip(), now, now.plus(TTL)));
        log.info("shop.invited shopId={} invitationId={}", shop.getUserId(), saved.getId());
        return toResponse(saved, shop.getShopName(), now);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> listForShop(Actor actor) {
        var shop = requireShop(actor);
        var now = clock.instant();
        return invitations.findByShopIdOrderByCreatedAtDesc(shop.getUserId()).stream()
                .map(i -> toResponse(i, shop.getShopName(), now)).toList();
    }

    @Transactional
    public void cancel(Actor actor, UUID invitationId) {
        var shop = requireShop(actor);
        var invitation = invitations.lockById(invitationId).orElseThrow(ShopInvitationService::invitationNotFound);
        if (!invitation.getShopId().equals(shop.getUserId())) throw invitationNotFound();
        if (invitation.getStatus() != ShopInvitation.Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_CLOSED", "This invitation is no longer pending.");
        }
        invitation.cancel(clock.instant());
    }

    /** "Gỡ thợ khỏi tiệm": the mechanic keeps their own partner account, no longer attached to the shop. */
    @Transactional
    public void removeStaff(Actor actor, UUID staffId) {
        var shop = requireShop(actor);
        var staff = profiles.lockById(staffId).orElseThrow(ShopInvitationService::staffNotFound);
        if (staff.getParentShopId() == null || !staff.getParentShopId().equals(shop.getUserId())) throw staffNotFound();
        detach(staff);
    }

    /** "Rời khỏi tiệm": a staff member leaves on their own. */
    @Transactional
    public void leave(Actor actor) {
        var staff = profiles.lockById(actor.userId()).orElseThrow(PartnerProfileService::notPartner);
        if (staff.getPartnerType() != PartnerType.SHOP_STAFF) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_SHOP_STAFF", "You are not part of a shop.");
        }
        detach(staff);
    }

    private void detach(PartnerProfile staff) {
        if (assignments.hasActiveJob(staff.getUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "PARTNER_HAS_ACTIVE_JOB", "Finish the open order first.");
        }
        staff.leaveShop();
        staff.setAvailability(Availability.OFFLINE);
    }

    // ------------------------------------------------------------------ the invited person

    @Transactional(readOnly = true)
    public List<InvitationResponse> listMine(Actor actor) {
        String phone = myPhone(actor);
        var now = clock.instant();
        return invitations.findByInviteePhoneAndStatusOrderByCreatedAtDesc(phone, ShopInvitation.Status.PENDING).stream()
                .filter(i -> i.isOpen(now))
                .map(i -> toResponse(i, shopName(i.getShopId()), now)).toList();
    }

    /** The invitee's own consent turns their account into shop staff. Returns their partner profile. */
    @Transactional
    public PartnerDtos.ProfileResponse accept(Actor actor, UUID invitationId) {
        var now = clock.instant();
        var invitation = lockMine(actor, invitationId, now);
        var shop = profiles.findById(invitation.getShopId()).orElse(null);
        if (shop == null || shop.getPartnerType() != PartnerType.SHOP || !shop.canTakeOrders()) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_UNAVAILABLE", "This shop can no longer take staff.");
        }
        var user = users.lockById(actor.userId()).orElseThrow(UserService::notFound);
        var existing = profiles.lockById(user.getId()).orElse(null);
        if (existing != null) {
            if (existing.getPartnerType() != PartnerType.INDIVIDUAL) {
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PARTNER", "Your account already belongs to a shop.");
            }
            if (assignments.hasActiveJob(user.getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "PARTNER_HAS_ACTIVE_JOB", "Finish your open order first.");
            }
            existing.joinShop(shop.getUserId());
        } else {
            if (orders.existsOpenOrderOf(user.getId(), openStatuses())) {
                throw new ApiException(HttpStatus.CONFLICT, "CUSTOMER_HAS_ACTIVE_ORDER",
                        "Finish or cancel your open rescue order before becoming shop staff.");
            }
            if (user.getFullName() == null) user.rename(invitation.getInviteeName());
            user.changeRole(Role.PARTNER);
            var staff = profiles.save(new PartnerProfile(user.getId(), PartnerType.SHOP_STAFF, shop.getUserId(), null));
            // Staff inherit the shop's service list until they manage their own; they still need their own KYC (RB-12).
            offers.findByPartnerId(shop.getUserId())
                    .forEach(o -> offers.save(new PartnerServiceOffer(staff.getUserId(), o.getServiceId())));
        }
        invitation.accept(user.getId(), now);
        log.info("shop.invitation.accepted shopId={} userId={}", shop.getUserId(), user.getId());
        return profileService.me(actor);
    }

    @Transactional
    public void decline(Actor actor, UUID invitationId) {
        var now = clock.instant();
        lockMine(actor, invitationId, now).decline(actor.userId(), now);
    }

    // ------------------------------------------------------------------ helpers

    private ShopInvitation lockMine(Actor actor, UUID invitationId, Instant now) {
        String phone = myPhone(actor);
        var invitation = invitations.lockById(invitationId).orElseThrow(ShopInvitationService::invitationNotFound);
        if (!invitation.getInviteePhone().equals(phone)) throw invitationNotFound();      // not yours: no existence leak
        if (!invitation.isOpen(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_CLOSED", "This invitation is no longer open.");
        }
        return invitation;
    }

    private String myPhone(Actor actor) {
        if (actor.is(Role.ADMIN)) throw forbidden();
        return identities.findPrimaryUid(actor.userId()).orElseThrow(ShopInvitationService::invitationNotFound);
    }

    private PartnerProfile requireShop(Actor actor) {
        var shop = profiles.findById(actor.userId()).orElseThrow(PartnerProfileService::notPartner);
        if (shop.getPartnerType() != PartnerType.SHOP) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SHOP_OWNER_ONLY", "Only a shop owner can manage staff.");
        }
        return shop;
    }

    /** BR06: only a shop the admin has approved may recruit. */
    private PartnerProfile requireApprovedShop(Actor actor) {
        var shop = requireShop(actor);
        if (!shop.canTakeOrders()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PARTNER_NOT_VERIFIED", "Your shop must be approved before inviting staff.");
        }
        return shop;
    }

    private String shopName(UUID shopId) {
        return profiles.findById(shopId).map(PartnerProfile::getShopName).orElse(null);
    }

    private static List<OrderStatus> openStatuses() {
        return Arrays.stream(OrderStatus.values()).filter(s -> !s.isTerminal()).toList();
    }

    private static InvitationResponse toResponse(ShopInvitation i, String shopName, Instant now) {
        String status = i.getStatus() == ShopInvitation.Status.PENDING && !i.isOpen(now) ? "EXPIRED" : i.getStatus().name();
        return new InvitationResponse(i.getId(), i.getShopId(), shopName, i.getInviteePhone(), i.getInviteeName(), status,
                i.getCreatedAt(), i.getExpiresAt());
    }

    private static ApiException invitationNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Invitation does not exist.");
    }

    private static ApiException staffNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "This mechanic is not part of your shop.");
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action.");
    }
}
