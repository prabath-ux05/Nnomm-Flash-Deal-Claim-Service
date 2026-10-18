-- V3 migration: Seed active flash deals for immediate evaluation and local development
-- Inserts sample deals with active time windows and stock available.

INSERT INTO deals (
    id,
    title,
    description,
    price_paise,
    stock_total,
    stock_available,
    starts_at,
    ends_at,
    created_at,
    updated_at
) VALUES (
    '11111111-1111-1111-1111-111111111111',
    'Wireless Noise-Canceling Headphones',
    'Premium wireless headphones with active noise cancellation',
    299900,
    50,
    50,
    CURRENT_TIMESTAMP - INTERVAL '1 day',
    CURRENT_TIMESTAMP + INTERVAL '30 days',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
), (
    '22222222-2222-2222-2222-222222222222',
    'Mechanical Gaming Keyboard',
    'RGB hot-swappable mechanical keyboard with linear switches',
    499900,
    30,
    30,
    CURRENT_TIMESTAMP - INTERVAL '1 day',
    CURRENT_TIMESTAMP + INTERVAL '30 days',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (id) DO NOTHING;
