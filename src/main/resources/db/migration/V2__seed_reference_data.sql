-- Reference data (RB-23): fees and rates live in the database, never as constants in code.
INSERT INTO services (id, code, name, description, base_price, is_active, sort_order) VALUES
    ('11111111-0000-4000-8000-000000000001', 'tire-patch',    'Vá xe lưu động',          'Thủng lốp, cán đinh',            80000, TRUE, 1),
    ('11111111-0000-4000-8000-000000000002', 'tire-pump',     'Bơm lốp tận nơi',         'Non hơi, xuống lốp',             50000, TRUE, 2),
    ('11111111-0000-4000-8000-000000000003', 'tube-replace',  'Thay ruột xe',            'Xe số 130k · tay ga 160k',      130000, TRUE, 3),
    ('11111111-0000-4000-8000-000000000004', 'battery-jump',  'Kích bình ắc quy',        'Hết điện, không đề được',        80000, TRUE, 4),
    ('11111111-0000-4000-8000-000000000005', 'chain-fix',     'Tăng sên',                'Tuột xích, kẹt sên máy',         60000, TRUE, 5),
    ('11111111-0000-4000-8000-000000000006', 'chain-clean',   'Vệ sinh / bôi trơn sên',  'Sên khô, kêu rít khi chạy',      90000, TRUE, 6),
    ('11111111-0000-4000-8000-000000000007', 'oil-change',    'Thay nhớt tận nơi',       'Cạn nhớt, bó máy dọc đường',    180000, TRUE, 7),
    ('11111111-0000-4000-8000-000000000008', 'towing',        'Chở / kéo xe về tiệm',    'Hỏng nặng · +10–12k/km',         60000, TRUE, 8);

INSERT INTO call_out_fee_configs (id, scope_type, scope_value, fee_amount, effective_from, effective_to, created_by)
VALUES ('22222222-0000-4000-8000-000000000001', 'GLOBAL', NULL, 30000, TIMESTAMPTZ '2026-01-01 00:00:00+07', NULL, NULL);

INSERT INTO commission_configs (id, scope_type, scope_value, commission_rate, commission_base, effective_from, effective_to, created_by)
VALUES ('33333333-0000-4000-8000-000000000001', 'GLOBAL', NULL, 0.1000, 'ORDER_TOTAL', TIMESTAMPTZ '2026-01-01 00:00:00+07', NULL, NULL);

-- BR08: broadcast widens after 60–90 s without an acceptance; last round is final (RB-26).
INSERT INTO dispatch_policies (id, scope_type, scope_value, round_no, radius_m, timeout_seconds, include_lower_priority, is_final_round, effective_from, effective_to) VALUES
    ('44444444-0000-4000-8000-000000000001', 'GLOBAL', NULL, 1, 2000, 60, FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+07', NULL),
    ('44444444-0000-4000-8000-000000000002', 'GLOBAL', NULL, 2, 4000, 75, TRUE,  FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+07', NULL),
    ('44444444-0000-4000-8000-000000000003', 'GLOBAL', NULL, 3, 7000, 90, TRUE,  TRUE,  TIMESTAMPTZ '2026-01-01 00:00:00+07', NULL);
