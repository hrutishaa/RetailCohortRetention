-- name: retention_matrix
-- share of each cohort that ordered again in month N
WITH sizes AS (
  SELECT cohort_month, COUNT(*) AS cohort_size
  FROM customer_months WHERE month_index = 0 GROUP BY cohort_month
)
SELECT cm.cohort_month, s.cohort_size, cm.month_index,
       COUNT(*) AS active_customers,
       ROUND(100.0 * COUNT(*) / s.cohort_size, 2) AS retention_pct,
       ROUND(SUM(cm.revenue), 2) AS revenue
FROM customer_months cm JOIN sizes s USING (cohort_month)
GROUP BY cm.cohort_month, s.cohort_size, cm.month_index
ORDER BY cm.cohort_month, cm.month_index;

-- name: avg_retention_curve
-- size-weighted average across new-customer cohorts (Dec 2009 is the pre-existing base, kept out)
-- only cohorts old enough to have reached month N are counted for that N
WITH sizes AS (
  SELECT cohort_month, COUNT(*) AS cohort_size
  FROM customer_months WHERE month_index = 0 GROUP BY cohort_month
), observable AS (
  SELECT s.cohort_month, s.cohort_size, m.month_index
  FROM sizes s
  JOIN (SELECT DISTINCT month_index FROM customer_months) m
  WHERE s.cohort_month > '2009-12'
    AND (CAST(substr('2011-11',1,4) AS INT) - CAST(substr(s.cohort_month,1,4) AS INT)) * 12
        + 11 - CAST(substr(s.cohort_month,6,2) AS INT) >= m.month_index
), active AS (
  SELECT cohort_month, month_index, COUNT(*) AS n FROM customer_months GROUP BY cohort_month, month_index
)
SELECT o.month_index,
       COUNT(*) AS cohorts,
       SUM(o.cohort_size) AS customers,
       ROUND(100.0 * SUM(COALESCE(a.n, 0)) / SUM(o.cohort_size), 2) AS retention_pct
FROM observable o LEFT JOIN active a USING (cohort_month, month_index)
GROUP BY o.month_index ORDER BY o.month_index;

-- name: base_cohort_curve
SELECT month_index, COUNT(*) AS active_customers,
       ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM customer_months WHERE cohort_month = '2009-12' AND month_index = 0), 2) AS retention_pct
FROM customer_months WHERE cohort_month = '2009-12'
GROUP BY month_index ORDER BY month_index;

-- name: repeat_behaviour
WITH ranked AS (
  SELECT customer_id, order_ts, revenue,
         ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_ts) AS n,
         LAG(order_ts) OVER (PARTITION BY customer_id ORDER BY order_ts) AS prev_ts
  FROM orders
), per_customer AS (
  SELECT customer_id, COUNT(*) AS orders, SUM(revenue) AS revenue,
         MAX(CASE WHEN n = 2 THEN julianday(order_ts) - julianday(prev_ts) END) AS days_to_second
  FROM ranked GROUP BY customer_id
)
SELECT COUNT(*) AS customers,
       SUM(orders) AS orders,
       ROUND(SUM(revenue), 2) AS revenue,
       ROUND(100.0 * SUM(orders >= 2) / COUNT(*), 2) AS repeat_customer_pct,
       ROUND(100.0 * SUM(CASE WHEN orders >= 2 THEN revenue ELSE 0 END) / SUM(revenue), 2) AS repeat_revenue_pct,
       ROUND(SUM(revenue) / SUM(orders), 2) AS avg_order_value
FROM per_customer;

-- name: days_to_second_order
WITH ranked AS (
  SELECT customer_id,
         julianday(order_ts) - julianday(LAG(order_ts) OVER (PARTITION BY customer_id ORDER BY order_ts)) AS gap,
         ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_ts) AS n
  FROM orders
)
SELECT ROUND(gap, 1) AS days FROM ranked WHERE n = 2 ORDER BY gap;

-- name: second_order_by_first_month_spend
-- does a bigger first order predict coming back? quartiles of first-order value
WITH first_orders AS (
  SELECT customer_id, revenue,
         ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_ts) AS n
  FROM orders
), q AS (
  SELECT customer_id, revenue, NTILE(4) OVER (ORDER BY revenue, customer_id) AS quartile
  FROM first_orders WHERE n = 1
), counts AS (SELECT customer_id, COUNT(*) AS orders FROM orders GROUP BY customer_id)
SELECT quartile, COUNT(*) AS customers,
       ROUND(MIN(revenue), 2) AS min_first_order, ROUND(MAX(revenue), 2) AS max_first_order,
       ROUND(100.0 * SUM(c.orders >= 2) / COUNT(*), 2) AS repeat_pct
FROM q JOIN counts c USING (customer_id)
GROUP BY quartile ORDER BY quartile;

-- name: ltv_by_cohort_quarter
-- cumulative revenue per acquired customer after month 0, 3, 6 and 12, by acquisition quarter.
-- only cohorts with a full 12 months of history (Jan 2010 to Nov 2010) are included.
WITH sizes AS (
  SELECT cohort_month, COUNT(*) AS cohort_size FROM customer_months
  WHERE month_index = 0 AND cohort_month BETWEEN '2010-01' AND '2010-11'
  GROUP BY cohort_month
), q AS (
  SELECT cohort_month, cohort_size,
         substr(cohort_month,1,4) || '-Q' || ((CAST(substr(cohort_month,6,2) AS INT) + 2) / 3) AS cohort_quarter
  FROM sizes
), qs AS (
  SELECT cohort_quarter, SUM(cohort_size) AS customers FROM q GROUP BY cohort_quarter
), rev AS (
  SELECT q.cohort_quarter,
         SUM(CASE WHEN month_index = 0  THEN revenue ELSE 0 END) AS r0,
         SUM(CASE WHEN month_index <= 3  THEN revenue ELSE 0 END) AS r3,
         SUM(CASE WHEN month_index <= 6  THEN revenue ELSE 0 END) AS r6,
         SUM(CASE WHEN month_index <= 12 THEN revenue ELSE 0 END) AS r12
  FROM customer_months c JOIN q USING (cohort_month)
  GROUP BY q.cohort_quarter
)
SELECT qs.cohort_quarter, qs.customers,
       ROUND(r0 / customers, 2) AS ltv_m0, ROUND(r3 / customers, 2) AS ltv_m3,
       ROUND(r6 / customers, 2) AS ltv_m6, ROUND(r12 / customers, 2) AS ltv_m12
FROM qs JOIN rev USING (cohort_quarter)
ORDER BY qs.cohort_quarter;

-- name: monthly_active_mix
-- each month's active customers split into new vs returning
SELECT order_month,
       SUM(month_index = 0) AS new_customers,
       SUM(month_index > 0) AS returning_customers,
       ROUND(SUM(CASE WHEN month_index = 0 THEN revenue ELSE 0 END), 2) AS new_revenue,
       ROUND(SUM(CASE WHEN month_index > 0 THEN revenue ELSE 0 END), 2) AS returning_revenue
FROM customer_months
GROUP BY order_month ORDER BY order_month;

-- name: rfm_segments
-- RFM as of 2011-12-01 using quintiles; segment names follow the usual R/F grid
WITH base AS (
  SELECT customer_id,
         CAST(julianday('2011-12-01') - julianday(MAX(order_ts)) AS INT) AS recency_days,
         COUNT(*) AS frequency,
         SUM(revenue) AS monetary
  FROM orders GROUP BY customer_id
), scored AS (
  SELECT *,
         NTILE(5) OVER (ORDER BY recency_days DESC, customer_id) AS r,
         NTILE(5) OVER (ORDER BY frequency, customer_id) AS f,
         NTILE(5) OVER (ORDER BY monetary, customer_id) AS m
  FROM base
), labelled AS (
  SELECT *,
    CASE
      WHEN r >= 4 AND f >= 4 THEN 'Champions'
      WHEN r >= 3 AND f >= 3 THEN 'Loyal'
      WHEN r >= 4 AND f <= 2 THEN 'New or Promising'
      WHEN r <= 2 AND f >= 3 THEN 'At Risk'
      WHEN r = 3 AND f <= 2 THEN 'Needs Attention'
      ELSE 'Hibernating'
    END AS segment
  FROM scored
)
SELECT segment,
       COUNT(*) AS customers,
       ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM labelled), 2) AS customer_pct,
       ROUND(SUM(monetary), 2) AS revenue,
       ROUND(100.0 * SUM(monetary) / (SELECT SUM(monetary) FROM labelled), 2) AS revenue_pct,
       ROUND(AVG(recency_days), 1) AS avg_recency_days,
       ROUND(AVG(frequency), 2) AS avg_orders,
       ROUND(AVG(monetary), 2) AS avg_revenue
FROM labelled GROUP BY segment ORDER BY revenue DESC;

-- name: acquisition_by_year
-- new customers acquired Jan-Nov, so both years cover the same 11 months
SELECT substr(cohort_month, 1, 4) AS year,
       COUNT(*) AS new_customers,
       ROUND(SUM(revenue), 2) AS first_month_revenue
FROM customer_months
WHERE month_index = 0 AND substr(cohort_month, 6, 2) <> '12'
GROUP BY year ORDER BY year;
