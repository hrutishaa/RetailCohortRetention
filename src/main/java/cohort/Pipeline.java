package cohort;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Load, clean, model and query steps, kept separate from main() so tests can drive them. */
public class Pipeline implements AutoCloseable {
    private final Connection conn;
    private final Path sqlDir;

    public Pipeline(Path sqlDir) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        this.sqlDir = sqlDir;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE raw_lines (
                  row_id INTEGER PRIMARY KEY, invoice TEXT, stock_code TEXT, description TEXT,
                  quantity INTEGER, invoice_date TEXT, price REAL, customer_id TEXT,
                  country TEXT, source_sheet TEXT)""");
        }
    }

    public Connection connection() { return conn; }

    public long load(Path csvGz) throws IOException, SQLException {
        try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(csvGz)), StandardCharsets.UTF_8)) {
            return load(r);
        }
    }

    public long load(Reader reader) throws IOException, SQLException {
        conn.setAutoCommit(false);
        long n = 0;
        String insert = "INSERT INTO raw_lines(invoice, stock_code, description, quantity, invoice_date, price, customer_id, country, source_sheet) VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
            for (CSVRecord rec : fmt.parse(reader)) {
                ps.setString(1, rec.get("invoice"));
                ps.setString(2, rec.get("stock_code"));
                ps.setString(3, rec.get("description"));
                ps.setLong(4, Long.parseLong(rec.get("quantity")));
                ps.setString(5, rec.get("invoice_date"));
                ps.setDouble(6, Double.parseDouble(rec.get("price")));
                ps.setString(7, rec.get("customer_id"));
                ps.setString(8, rec.get("country"));
                ps.setString(9, rec.get("source_sheet"));
                ps.addBatch();
                if (++n % 50_000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
        return n;
    }

    /** Runs each cleaning step and returns rows removed per step, in order. */
    public LinkedHashMap<String, Long> clean() throws IOException, SQLException {
        LinkedHashMap<String, Long> removed = new LinkedHashMap<>();
        try (Statement st = conn.createStatement()) {
            for (SqlScript.Block b : SqlScript.read(sqlDir.resolve("01_clean.sql"))) {
                removed.put(b.name(), (long) st.executeUpdate(b.sql()));
            }
        }
        return removed;
    }

    public void model() throws IOException, SQLException {
        try (Statement st = conn.createStatement()) {
            for (SqlScript.Block b : SqlScript.read(sqlDir.resolve("02_model.sql"))) st.execute(b.sql());
        }
    }

    public LinkedHashMap<String, List<Map<String, Object>>> metrics() throws IOException, SQLException {
        LinkedHashMap<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (SqlScript.Block b : SqlScript.read(sqlDir.resolve("03_metrics.sql"))) out.put(b.name(), query(b.sql()));
        return out;
    }

    public long count(String table) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public List<Map<String, Object>> query(String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public void close() throws SQLException { conn.close(); }
}
