-- Reference data (RB-23): additional rescue services for the catalog. Fixed UUIDs so the seed can offer them by code.
INSERT INTO services (id, code, name, description, base_price, is_active, sort_order) VALUES
    ('11111111-0000-4000-8000-000000000009', 'brake-fix',       'Sửa / chỉnh phanh',        'Phanh ăn kém, kẹt phanh',        90000, TRUE,  9),
    ('11111111-0000-4000-8000-00000000000a', 'light-fix',        'Sửa đèn / còi / xi-nhan',  'Đèn không sáng, còi hỏng',       70000, TRUE, 10),
    ('11111111-0000-4000-8000-00000000000b', 'spark-plug',       'Thay bugi',                'Xe yếu, khó nổ do bugi',         60000, TRUE, 11),
    ('11111111-0000-4000-8000-00000000000c', 'fuel-delivery',    'Tiếp xăng dọc đường',      'Hết xăng giữa đường',            40000, TRUE, 12);
