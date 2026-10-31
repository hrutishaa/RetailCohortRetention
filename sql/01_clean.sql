-- Cleaning rules. Each step is logged with how many rows it removed (see RunAnalysis).
-- The raw table is raw_lines, loaded straight from data/online_retail_ii.csv.gz.

-- step: overlap_between_sheets
-- The two workbook sheets overlap for 2010-12-01..2010-12-09. Keep the 2010-2011 copy.
DELETE FROM raw_lines
WHERE source_sheet = 'Year 2009-2010' AND invoice_date >= '2010-12-01';

-- step: exact_duplicates
DELETE FROM raw_lines
WHERE row_id NOT IN (
  SELECT MIN(row_id) FROM raw_lines
  GROUP BY invoice, stock_code, description, quantity, invoice_date, price, customer_id
);

-- step: missing_customer_id
DELETE FROM raw_lines WHERE customer_id IS NULL OR customer_id = '';

-- step: cancellations
DELETE FROM raw_lines WHERE invoice LIKE 'C%';

-- step: non_positive_qty_or_price
DELETE FROM raw_lines WHERE quantity <= 0 OR price <= 0;

-- step: non_product_codes
-- postage, bank charges, manual adjustments, Amazon fees, samples, etc.
DELETE FROM raw_lines WHERE NOT (stock_code GLOB '[0-9][0-9][0-9][0-9][0-9]*');

-- step: incomplete_last_month
-- data stops on 2011-12-09, so December 2011 would look like a retention collapse
DELETE FROM raw_lines WHERE invoice_date >= '2011-12-01';
