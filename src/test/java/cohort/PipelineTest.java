package cohort;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real SQL on a handful of hand-made rows where the right answer is easy to check. */
class PipelineTest {
    private static final String HEADER = "invoice,stock_code,description,quantity,invoice_date,price,customer_id,country,source_sheet\n";
    private static final String A = "Year 2009-2010", B = "Year 2010-2011";

    private static String row(String inv, String code, int qty, String date, double price, String cust, String sheet) {
        return String.join(",", inv, code, "ITEM", String.valueOf(qty), date, String.valueOf(price), cust, "United Kingdom", sheet) + "\n";
    }

    private Pipeline loaded(String csv) throws Exception {
        Pipeline p = new Pipeline(Path.of("sql"));
        p.load(new StringReader(HEADER + csv));
        return p;
    }

    @Test
    void eachCleaningRuleRemovesWhatItShould() throws Exception {
        String csv = row("1001", "85048", 2, "2010-01-05 10:00:00", 5.0, "1", A)
                + row("1001", "85048", 2, "2010-01-05 10:00:00", 5.0, "1", A)   // exact duplicate
                + row("1002", "85048", 1, "2010-12-02 09:00:00", 5.0, "1", A)   // overlap copy
                + row("1002", "85048", 1, "2010-12-02 09:00:00", 5.0, "1", B)   // kept
                + row("1003", "22041", 3, "2010-02-01 09:00:00", 2.0, "", A)    // no customer
                + row("C1004", "22041", -3, "2010-02-03 09:00:00", 2.0, "1", A) // cancellation
                + row("1005", "22041", 3, "2010-02-04 09:00:00", 0.0, "1", A)   // zero price
                + row("1006", "POST", 1, "2010-02-05 09:00:00", 18.0, "1", A)   // postage
                + row("1007", "22041", 1, "2011-12-05 09:00:00", 2.0, "1", B);  // partial month
        try (Pipeline p = loaded(csv)) {
            Map<String, Long> removed = p.clean();
            assertEquals(1L, removed.get("overlap_between_sheets"));
            assertEquals(1L, removed.get("exact_duplicates"));
            assertEquals(1L, removed.get("missing_customer_id"));
            assertEquals(1L, removed.get("cancellations"));
            assertEquals(1L, removed.get("non_positive_qty_or_price"));
            assertEquals(1L, removed.get("non_product_codes"));
            assertEquals(1L, removed.get("incomplete_last_month"));
            assertEquals(2, p.count("raw_lines"));
        }
    }

    @Test
    void retentionCountsCustomersActiveInLaterMonths() throws Exception {
        // customers 1 and 2 start in Jan 2010; only customer 1 comes back in March
        String csv = row("2001", "85048", 1, "2010-01-10 10:00:00", 10.0, "1", A)
                + row("2002", "85048", 1, "2010-01-11 10:00:00", 10.0, "2", A)
                + row("2003", "85048", 3, "2010-03-02 10:00:00", 10.0, "1", A);
        try (Pipeline p = loaded(csv)) {
            p.clean();
            p.model();
            List<Map<String, Object>> m = p.metrics().get("retention_matrix");
            assertEquals(2, m.size());
            assertEquals(2, ((Number) m.get(0).get("cohort_size")).intValue());
            assertEquals(2, ((Number) m.get(1).get("month_index")).intValue());
            assertEquals(50.0, ((Number) m.get(1).get("retention_pct")).doubleValue(), 1e-9);
            assertEquals(30.0, ((Number) m.get(1).get("revenue")).doubleValue(), 1e-9);
        }
    }

    @Test
    void percentileInterpolates() {
        assertEquals(2.5, RunAnalysis.percentile(List.of(1.0, 2.0, 3.0, 4.0), 50), 1e-9);
        assertEquals(1.0, RunAnalysis.percentile(List.of(1.0, 2.0, 3.0, 4.0), 0), 1e-9);
    }
}
