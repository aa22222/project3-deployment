BEGIN;

-- Simulated quantities per drink.
-- Liquids use liters; powders and toppings use kilograms.
INSERT INTO menu_item_ingredient
    (menu_item_id, inventory_item_id, quantity_required)
VALUES
    -- Classic Milk Tea: black tea, milk, pearls
    (1, 1, 0.2000), (1, 4, 0.1500), (1, 19, 0.0500),

    -- Jasmine Milk Tea: jasmine tea, milk, pearls
    (2, 2, 0.2000), (2, 4, 0.1500), (2, 19, 0.0500),

    -- Taro Milk Tea: taro powder, jasmine tea, milk
    (3, 6, 0.0300), (3, 2, 0.1500), (3, 4, 0.1500),

    -- Matcha Milk Tea: matcha powder, milk, honey
    (4, 7, 0.0080), (4, 4, 0.2500), (4, 15, 0.0200),

    -- Thai Milk Tea: Thai tea mix, creamer, milk
    (5, 8, 0.0200), (5, 5, 0.0200), (5, 4, 0.1500),

    -- Brown Sugar Milk Tea: black tea, milk, brown sugar syrup
    (6, 1, 0.1500), (6, 4, 0.2000), (6, 9, 0.0300),

    -- Oolong Milk Tea: oolong tea, milk, pearls
    (7, 3, 0.2000), (7, 4, 0.1500), (7, 19, 0.0500),

    -- Wintermelon Milk Tea: black tea, milk, wintermelon syrup
    (8, 1, 0.2000), (8, 4, 0.1500), (8, 10, 0.0300),

    -- Mango Green Tea
    (9, 2, 0.3000), (9, 11, 0.0500),

    -- Passion Fruit Green Tea
    (10, 2, 0.3000), (10, 12, 0.0400),

    -- Peach Oolong Tea
    (11, 3, 0.3000), (11, 13, 0.0400),

    -- Strawberry Green Tea
    (12, 2, 0.3000), (12, 14, 0.0500),

    -- Honey Lemon Tea: black tea, honey, lemon juice
    (13, 1, 0.3000), (13, 15, 0.0250), (13, 16, 0.0250),

    -- Lychee Black Tea
    (14, 1, 0.3000), (14, 17, 0.0400),

    -- Classic Black Tea
    (15, 1, 0.3500),

    -- Jasmine Green Tea
    (16, 2, 0.3500),

    -- Wintermelon Tea: syrup diluted with untracked water
    (17, 10, 0.0500),

    -- Mango Smoothie: puree, smoothie base, milk
    (18, 11, 0.0800), (18, 18, 0.0400), (18, 4, 0.1500),

    -- Taro Smoothie: powder, smoothie base, milk
    (19, 6, 0.0400), (19, 18, 0.0400), (19, 4, 0.1500),

    -- Strawberry Smoothie: puree, smoothie base, milk
    (20, 14, 0.0800), (20, 18, 0.0400), (20, 4, 0.1500);

-- Every drink uses one cup, straw, film unit, and napkin.
INSERT INTO menu_item_ingredient
    (menu_item_id, inventory_item_id, quantity_required)
SELECT m.menu_item_id, supplies.inventory_item_id, 1.0000
FROM menu_item m
CROSS JOIN (
    VALUES (23), (24), (25), (26)
) AS supplies(inventory_item_id)
WHERE m.menu_item_id BETWEEN 1 AND 20;

-- Verify before issuing COMMIT in the same session.
COMMIT;