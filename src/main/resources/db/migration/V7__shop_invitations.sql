-- A shop owner invites a person by phone; nothing changes for that person until they accept (AUTHZ §6.3).
CREATE TABLE shop_invitations (
    id UUID PRIMARY KEY,
    shop_id UUID NOT NULL REFERENCES partner_profiles(user_id),
    invitee_phone VARCHAR(20) NOT NULL,
    invitee_name VARCHAR(100) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    invitee_user_id UUID REFERENCES app_users(id)
);
CREATE UNIQUE INDEX uk_shop_invitations_pending ON shop_invitations(shop_id, invitee_phone) WHERE status = 'PENDING';
CREATE INDEX idx_shop_invitations_phone ON shop_invitations(invitee_phone, status);
