-- Historical and old-writer values remain NULL: their original input is unknown.
ALTER TABLE management_value_events ADD COLUMN benefit_provenance VARCHAR(16) NULL;
