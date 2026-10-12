-- Customer profile fields. All columns are nullable so existing accounts remain valid.
ALTER TABLE app_users
    ADD COLUMN email VARCHAR(254),
    ADD COLUMN date_of_birth DATE,
    ADD COLUMN avatar_url TEXT;
