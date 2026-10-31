package cohort;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.*;
import java.util.*;

public class RunAnalysis {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args.length > 0 ? args[0] : "data/online_retail_ii.csv.gz");
        Map<String, Object> results = new LinkedHashMap<>();
        try (Pipeline p = new Pipeline(Path.of("sql"))) {
            long raw = p.load(data);
            LinkedHashMap<String, Long> removed = p.clean();
            long clean = p.count("raw_lines");
            p.model();
            var metrics = p.metrics();

            List<Double> gaps = metrics.remove("days_to_second_order").stream()
                    .map(r -> ((Number) r.get("days")).doubleValue()).toList();

            results.put("source", "UCI Online Retail II (id 502), CC BY 4.0");
            results.put("raw_rows", raw);
            results.put("rows_removed_by_rule", removed);
            results.put("clean_rows", clean);
            results.put("orders", p.count("orders"));
            results.put("customers", p.query("SELECT COUNT(DISTINCT customer_id) AS n FROM orders").get(0).get("n"));
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("customers_with_second_order", gaps.size());
            gap.put("p25", percentile(gaps, 25));
            gap.put("median", percentile(gaps, 50));
            gap.put("p75", percentile(gaps, 75));
            results.put("days_to_second_order", gap);
            results.putAll(metrics);
        }
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.createDirectories(Path.of("docs"));
        String json = om.writeValueAsString(results);
        Files.writeString(Path.of("docs/results.json"), json);
        Files.writeString(Path.of("docs/results.js"), "window.RESULTS = " + json + ";\n");
        printSummary(results);
    }

    static double percentile(List<Double> sorted, double pct) {
        if (sorted.isEmpty()) return Double.NaN;
        double rank = pct / 100.0 * (sorted.size() - 1);
        int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
        double v = sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (rank - lo);
        return Math.round(v * 10) / 10.0;
    }

    @SuppressWarnings("unchecked")
    private static void printSummary(Map<String, Object> r) {
        System.out.printf("raw rows %s -> clean rows %s (%s orders, %s customers)%n",
                r.get("raw_rows"), r.get("clean_rows"), r.get("orders"), r.get("customers"));
        System.out.println("removed by rule: " + r.get("rows_removed_by_rule"));
        System.out.println("repeat behaviour: " + r.get("repeat_behaviour"));
        System.out.println("days to 2nd order: " + r.get("days_to_second_order"));
        for (Map<String, Object> row : (List<Map<String, Object>>) r.get("avg_retention_curve")) {
            int m = ((Number) row.get("month_index")).intValue();
            if (m == 1 || m == 3 || m == 6 || m == 12) System.out.println("avg retention " + row);
        }
        System.out.println("first-order quartiles: " + r.get("second_order_by_first_month_spend"));
        System.out.println("ltv: " + r.get("ltv_by_cohort_quarter"));
        System.out.println("rfm: " + r.get("rfm_segments"));
    }
}
