# Retail Cohort Retention

How well does a UK online gift retailer keep the customers it wins? This project answers that with SQL: monthly acquisition cohorts, a retention heatmap, cumulative revenue per customer (LTV) and an RFM segmentation, all built from two years of real transaction lines.

**Live dashboard:** https://hrutishaa.github.io/RetailCohortRetention/

## Data

[Online Retail II](https://archive.ics.uci.edu/dataset/502/online+retail+ii), UCI Machine Learning Repository (Daqing Chen, 2019, DOI [10.24432/C5CG6D](https://doi.org/10.24432/C5CG6D)). Licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). It holds every invoice line for a UK-based, non-store online retailer between 1 Dec 2009 and 9 Dec 2011, about 1.07M rows.

`data/online_retail_ii.csv.gz` is the original workbook with both sheets stacked into one CSV (`ConvertWorkbook.java`); nothing else is changed in that file. All cleaning happens in SQL so it can be read and audited.

## How it works

```
data/online_retail_ii.csv.gz
  -> load into SQLite (JDBC)
  -> sql/01_clean.sql   7 cleaning rules, each one logged with rows removed
  -> sql/02_model.sql   orders table + one row per customer per active month
  -> sql/03_metrics.sql retention matrix, retention curve, LTV, RFM, repeat behaviour
  -> docs/results.json  -> docs/index.html (Chart.js dashboard on GitHub Pages)
```

The SQL leans on CTEs and window functions (`ROW_NUMBER`, `LAG`, `NTILE`) rather than doing the math in Java. Java just loads the file, runs the scripts in order and writes the JSON.

```
mvn test        # unit tests on hand-built rows, including one per cleaning rule
mvn exec:java   # full run on the real data, about 10 seconds
```

Needs Java 17 and Maven. CI runs both and fails if the committed `docs/results.json` doesn't match a fresh run.

## Cleaning

| Rule | Rows removed |
|---|---:|
| Raw rows | 1,067,371 |
| Overlap between the two sheets (1 to 9 Dec 2010 appears in both) | 22,523 |
| Exact duplicate lines | 11,812 |
| Missing customer ID (can't be put in a cohort) | 235,151 |
| Cancellations (invoice starts with C) | 18,390 |
| Zero or negative quantity or price | 70 |
| Non-product codes (postage, fees, manual adjustments) | 2,848 |
| December 2011 (only 9 days of data, would look like churn) | 16,964 |
| **Clean rows** | **759,613** |

The sheet overlap is easy to miss: if you just stack the two sheets, the first week of December 2010 is counted twice.

## What I found

5,824 customers placed 35,818 orders worth £16.56M in net product revenue (average order £462).

1. **About 1 in 5 new customers comes back the next month, and that holds.** Across the 23 new-customer cohorts (Jan 2010 to Nov 2011), month-1 retention averages 20.97% and month-12 is still 18.43%. The curve flattens quickly, so the customers who return once tend to stick around.
2. **Repeat customers are the business.** 71.72% of customers ordered more than once, and they produced 96.58% of revenue. The median gap between first and second order is 57 days (middle half: 21 to 133 days), which is a natural window for a "come back" campaign.
3. **First-order size predicts return.** Customers in the top quartile of first-order value (£446+) came back 80.29% of the time, against 61.13% for the bottom quartile (under £167).
4. **LTV builds over the first year.** Customers acquired in Q1 2010 spent £450 in their first month and £1,993 per head after 12 months. Later 2010 quarters reached £1,126 to £1,561.
5. **New-customer intake fell by more than half.** Jan to Nov 2011 brought in 1,509 new customers, down from 3,288 in the same months of 2010 (-54%). Growth in 2011 came almost entirely from existing customers.
6. **RFM points to where to act.** Champions are 24.66% of customers but 69.38% of revenue. The At Risk group (844 customers who used to order often but haven't in about a year) spent £1.60M historically, which makes them the obvious win-back list.

The December 2009 cohort is the retailer's existing base on the day the data starts, so it retains far better (35.02% in month 1, 37.64% in month 12). It's shown on the dashboard but kept out of the new-customer averages.

## Caveats

- 22% of raw lines have no customer ID. They're excluded, so these numbers describe identified customers only.
- Cohorts are based on the first order *in this dataset*. Some 2010 "new" customers may have bought before Dec 2009.
- Revenue is quantity x unit price on product lines, before postage and after removing cancelled invoices. Returns that were not booked as cancellations aren't netted out.
- Many customers are wholesalers, which explains the high repeat rate compared with typical consumer e-commerce.

## Layout

```
sql/                      cleaning, modeling and metric queries
src/main/java/cohort/     loader, SQL runner, results writer, workbook converter
src/test/java/cohort/     JUnit 5 tests
docs/                     dashboard + results.json (served by GitHub Pages)
```
