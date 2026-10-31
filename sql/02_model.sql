-- step: orders
CREATE TABLE orders AS
SELECT invoice,
       customer_id,
       MIN(invoice_date)                AS order_ts,
       substr(MIN(invoice_date), 1, 7)  AS order_month,
       ROUND(SUM(quantity * price), 2)  AS revenue,
       COUNT(*)                         AS lines
FROM raw_lines
GROUP BY invoice, customer_id;

-- step: customer_months
CREATE TABLE customer_months AS
WITH firsts AS (
  SELECT customer_id, MIN(order_month) AS cohort_month
  FROM orders GROUP BY customer_id
)
SELECT o.customer_id,
       f.cohort_month,
       o.order_month,
       (CAST(substr(o.order_month,1,4) AS INT) - CAST(substr(f.cohort_month,1,4) AS INT)) * 12
         + CAST(substr(o.order_month,6,2) AS INT) - CAST(substr(f.cohort_month,6,2) AS INT) AS month_index,
       SUM(o.revenue) AS revenue,
       COUNT(*)       AS orders
FROM orders o JOIN firsts f USING (customer_id)
GROUP BY o.customer_id, f.cohort_month, o.order_month;
