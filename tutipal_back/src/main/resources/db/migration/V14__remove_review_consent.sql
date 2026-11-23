-- Remove the retired review preference. Existing review_items are preserved.
ALTER TABLE users DROP COLUMN review_consent;
