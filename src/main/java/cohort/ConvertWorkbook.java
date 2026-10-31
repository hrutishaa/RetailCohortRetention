package cohort;

import com.github.pjfanning.xlsx.StreamingReader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * One-off step: turns the UCI workbook (two sheets, 2009-10 and 2010-11) into a single
 * gzipped CSV so the analysis doesn't need to parse a 45 MB xlsx on every run.
 * Usage: mvn -q exec:java -Dexec.mainClass=cohort.ConvertWorkbook -Dexec.args="online_retail_II.xlsx data/online_retail_ii.csv.gz"
 */
public class ConvertWorkbook {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        long rows = 0;
        DataFormatter fmt = new DataFormatter();
        try (InputStream is = Files.newInputStream(in);
             Workbook wb = StreamingReader.builder().rowCacheSize(500).bufferSize(8192).open(is);
             Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8);
             CSVPrinter csv = new CSVPrinter(w, CSVFormat.DEFAULT.builder()
                     .setHeader("invoice", "stock_code", "description", "quantity", "invoice_date", "price", "customer_id", "country", "source_sheet").build())) {
            for (Sheet sheet : wb) {
                boolean header = true;
                for (Row r : sheet) {
                    if (header) { header = false; continue; }
                    String invoice = fmt.formatCellValue(r.getCell(0));
                    if (invoice.isBlank()) continue;
                    Cell dateCell = r.getCell(4);
                    String date = dateCell == null ? "" : dateCell.getLocalDateTimeCellValue().format(TS);
                    Cell cust = r.getCell(6);
                    String customer = (cust == null || cust.getCellType() == CellType.BLANK) ? ""
                            : String.valueOf((long) cust.getNumericCellValue());
                    csv.printRecord(invoice, fmt.formatCellValue(r.getCell(1)), fmt.formatCellValue(r.getCell(2)).trim(),
                            (long) r.getCell(3).getNumericCellValue(), date, r.getCell(5).getNumericCellValue(),
                            customer, fmt.formatCellValue(r.getCell(7)), sheet.getSheetName());
                    rows++;
                }
            }
        }
        System.out.println("wrote " + rows + " rows to " + out);
    }
}
