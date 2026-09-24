package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in offline audit. Never constructs a gateway or a database writer. */
class PermitCorpusAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void exportConfiguredCorpus() throws Exception {
        String configured = System.getProperty("permit.audit.manifest");
        Assumptions.assumeTrue(configured != null, "Set permit.audit.manifest to opt in to local audit");
        Path manifestPath = Path.of(configured).toAbsolutePath();
        JsonNode manifest = JSON.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8)
                .replaceFirst("^\\uFEFF", ""));
        Path output = Path.of(System.getProperty("permit.audit.output", manifestPath.getParent().toString()));
        Files.createDirectories(output.resolve("documents"));
        var catalog = new DocxPermitProfileCatalog();
        var detector = new WordPermitFormatDetector(catalog);
        var reader = new WordPermitDocumentReader();
        var parser = new DocxSchedulePermitParser(reader, detector, new AirportCodeCatalog(),
                new PermitOperatorCatalog(), Clock.fixed(LocalDate.parse(manifest.path("end").asText())
                    .atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), ZoneId.of("Asia/Ho_Chi_Minh")));
        List<Map<String, Object>> results = new ArrayList<>();
        // Optional standalone report regeneration from an existing audited result.
        String savedResults = System.getProperty("permit.audit.results");
        if (savedResults != null) {
            List<Map<String, Object>> saved = JSON.readValue(Files.readString(Path.of(savedResults)),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            saved.forEach(row -> row.put("aliases", JSON.valueToTree(row.get("aliases"))));
            assertThat(saved).hasSize(manifest.path("files").size());
            assertThat(saved.stream().map(row -> row.get("sha256")).toList()).containsExactlyElementsOf(
                    java.util.stream.StreamSupport.stream(manifest.path("files").spliterator(), false)
                            .map(file -> (Object) file.path("sha256").asText()).toList());
            writeWorkbook(output.resolve("permit-format-audit.xlsx"), manifest, catalog.profiles(), saved);
            verifyWorkbook(output.resolve("permit-format-audit.xlsx"), saved.size(), catalog.profiles().size());
            return;
        }
        for (JsonNode file : manifest.path("files")) {
            Path source = Path.of(file.path("path").asText());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sha256", file.path("sha256").asText());
            row.put("path", source.toString());
            row.put("aliases", file.path("aliases"));
            row.put("carrier", "UNKNOWN"); row.put("profile", ""); row.put("flights", 0);
            row.put("resolution", "NOT_TESTED"); row.put("import", "NOT_TESTED");
            row.put("status", "READ_ERROR"); row.put("error", "");
            row.put("pendingCatalogFields", List.of());
            Path temporary = null;
            try {
                try (var input = Files.newInputStream(source)) {
                    var digest = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[8192];
                    for (int size; (size = input.read(buffer)) >= 0;) digest.update(buffer, 0, size);
                    if (!HexFormat.of().formatHex(digest.digest()).equals(file.path("sha256").asText()))
                        throw new IllegalStateException("Source changed since inventory; rerun inventory");
                }
                Path readable = source;
                if (!source.toString().matches("(?i).*\\.docx?")) {
                    temporary = Files.createTempFile(output, "word-", source.toString().toLowerCase(Locale.ROOT)
                            .contains(".docx_") ? ".docx" : ".doc");
                    Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING); readable = temporary;
                }
                WordPermitDocument document = reader.read(readable);
                row.put("carrier", carrier(document.rawContent()));
                StringBuilder dump = new StringBuilder(document.paragraphText());
                for (int i = 0; i < document.tables().size(); i++) {
                    dump.append("\nTABLE ").append(i).append(" CONTEXT ").append(document.tableContexts().get(i)).append("\n");
                    document.tables().get(i).forEach(cells -> dump.append(String.join(" | ", cells)).append("\n"));
                }
                Files.writeString(output.resolve("documents").resolve(row.get("sha256") + ".txt"), dump, StandardCharsets.UTF_8);
                // No permit identity is not proof of a non-permit: retain questionable
                // flight documents for manual review instead of boosting the pass rate.
                String applicationReason = nonPermitReason(document.rawContent());
                if (applicationReason != null && applicationReason.startsWith("Application")
                        && !hasIssuedHeading(document.paragraphText())) {
                    row.put("status", "NON_PERMIT"); row.put("error", applicationReason);
                } else if (!Pattern.compile("(?iu)\\b(?:LD|OF|O\\s*/\\s*F|QLB)\\s*[-:/ ]*\\d")
                        .matcher(document.rawContent()).find()) {
                    String reason = nonPermitReason(document.rawContent());
                    row.put("status", reason == null ? "UNCLASSIFIED" : "NON_PERMIT");
                    row.put("error", reason == null ? "No permit identity; manual classification required" : reason);
                } else {
                    row.put("status", "PARSE_ERROR");
                    // Parser performs its non-permit guard BEFORE profile detection.
                    try { row.put("profile", detector.detect(document, source.getFileName().toString()).id()); }
                    catch (vatm.aerosync.common.exception.FormatValidationException ignored) { row.put("status", "NO_PROFILE"); }
                    SchedulePermit permit = parser.parse(document, source.getFileName().toString());
                    row.put("profile", detector.detect(document, source.getFileName().toString()).id());
                    row.put("carrier", permit.operatorId()); row.put("permit", permit.normalizedPermitId());
                    row.put("flights", permit.flights().size()); row.put("oldFlights", permit.originalFlights().size());
                    row.put("reviewOnly", permit.reviewOnly());
                    row.put("aircraft", permit.flights().stream().map(f -> Objects.toString(f.sourceAircraftType(), "MISSING")).distinct().toList());
                    row.put("purposes", permit.flights().stream().map(f -> Objects.toString(f.purposeId(), "MISSING")).distinct().toList());
                    var context = new ProcessingContext(new FileIngestedEvent(1L, source.toString(),
                            file.path("sha256").asText(), FileSourceType.FILESYSTEM, false));
                    context.setSchedulePermit(permit);
                    new NormalizerStep(ZoneId.of("Asia/Ho_Chi_Minh")).normalize(context);
                    row.put("status", "READABLE");
                    try { new BusinessRuleValidatorStep().validate(context); }
                    catch (BusinessRuleException e) {
                        // CRAFT_ID / IATA-to-ICAO are catalog lookups, not parsed fields.
                        // Preserve each pending lookup separately; never fake resolved IDs.
                        var fields = e.getRowErrors().stream().filter(err -> !"BR-CRAFT".equals(err.code()))
                                .filter(err -> !("BR-SCHEDULE-AIRPORT".equals(err.code())
                                        && err.value() != null && err.value().matches("[A-Z]{3}"))).toList();
                        row.put("pendingCatalogFields", e.getRowErrors().stream().filter(err -> !fields.contains(err))
                                .map(err -> err.code() + "[row=" + err.rowNumber() + ",value=" + err.value() + "]").toList());
                        if (fields.isEmpty() && e.getRowErrors().isEmpty()) throw e;
                        if (!fields.isEmpty()) {
                            row.put("status", "FIELD_ERROR");
                            row.put("error", fields.stream().map(err -> err.code() + "[row=" + err.rowNumber()
                                    + ",field=" + err.field() + ",value=" + err.value() + "]").toList().toString());
                        }
                    }
                }
            } catch (NonPermitDocumentException e) { row.put("status", "NON_PERMIT"); row.put("error", e.getMessage()); }
            catch (Exception e) {
                if ("READABLE".equals(row.get("status"))) row.put("status", "FIELD_ERROR");
                row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            finally { if (temporary != null) Files.deleteIfExists(temporary); }
            results.add(row);
            if (results.size() % 100 == 0) System.out.println("AUDIT " + results.size() + "/" + manifest.path("files").size());
        }
        assertThat(results).noneSatisfy(row -> assertThat(Objects.toString(row.get("error")))
                .contains("PatternSyntaxException"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), results);
        writeWorkbook(output.resolve("permit-format-audit.xlsx"), manifest, catalog.profiles(), results);
        Map<String, Long> counts = new TreeMap<>();
        results.forEach(r -> counts.merge(r.get("status").toString(), 1L, Long::sum));
        System.out.println("AUDIT COMPLETE " + counts + " output=" + output);
        assertThat(results).hasSize(manifest.path("files").size()).isNotEmpty();
        verifyWorkbook(output.resolve("permit-format-audit.xlsx"), results.size(), catalog.profiles().size());
    }

    private void verifyWorkbook(Path path, int files, int profiles) throws Exception {
        try (var input = Files.newInputStream(path); var verified = new XSSFWorkbook(input)) {
            assertThat(verified.getSheet("Files").getLastRowNum()).isEqualTo(files);
            assertThat(verified.getSheet("Profiles").getLastRowNum()).isEqualTo(profiles);
            assertThat(verified.getNumberOfSheets()).isEqualTo(6);
        }
    }

    static boolean hasIssuedHeading(String text) {
        return Pattern.compile("(?imu)^.*(?:PERMIT\\s+(?:NO\\.?|NUMBER|NBR)|NUMBER|№|PHÉP\\s+BAY\\s+SỐ)"
                        + "[^\\r\\n]{0,30}(?:LD|OF|O\\s*/\\s*F|QLB)\\s*[-–—:/ ]*\\d")
                .matcher(text).find();
    }

    static String nonPermitReason(String raw) {
        String folded = java.text.Normalizer.normalize(raw.replace('Đ', 'D').replace('đ', 'd'),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT);
        if (Pattern.compile("(?m)^\\s*(?:FORM\\s*:\\s*)?(?:APPLICATION\\b|DE\\s+NGHI\\s+CAP\\s+PHEP|DON\\s+(?:XIN|DE\\s+NGHI)|PASSENGER\\s+LIST|DANH SACH HANH KHACH)")
                .matcher(folded.substring(0, Math.min(folded.length(), 1000))).find()) return "Application/passenger list, not issued permit";
        if (folded.contains("APPLICANT") && folded.contains("SUBMITTED DATE"))
            return "Application submitted by applicant; no issued permit number";
        if (folded.contains("KE HOACH BAY")) return "Flight plan, not issued permit";
        if (folded.contains("DANG KY MUA VE") || folded.contains("SINH HOAT CHI BO"))
            return "Administrative document, not issued permit";
        if (folded.contains("SCR") && folded.contains("SENT BY") && folded.contains("SLOT"))
            return "Slot coordination message, not issued permit";
        return null;
    }

    @Test
    void distinguishesApplicationsAndUnclassifiedDocuments() {
        assertThat(nonPermitReason("ĐƠN XIN PHÉP BAY")).contains("Application");
        assertThat(nonPermitReason("Applicant: Example Submitted date: 15/09/2026")).contains("Application");
        assertThat(nonPermitReason("KẾ HOẠCH BAY")).contains("Flight plan");
        assertThat(nonPermitReason("No recognizable heading")).isNull();
        assertThat(nonPermitReason("№: LD-88/A/S/2026VN\nAircraft Type (apply to application only)")).isNull();
        assertThat(hasIssuedHeading("Ha Noi, 10th April 2026\n№: LD - 88/A/S/2026VN")).isTrue();
        assertThat(carrier("IATA: SV ICAO Code: SVA")).isEqualTo("SVA");
    }

    static String carrier(String raw) {
        var icao = Pattern.compile("(?iu)ICAO\\s*(?:CODE)?(?:\\s*\\([^)]*\\))?[ \t]*:[ \t]*([A-Z0-9]{3})(?![A-Z0-9])").matcher(raw);
        if (icao.find()) return icao.group(1).toUpperCase(Locale.ROOT);
        var iata = Pattern.compile("(?iu)IATA\\s*(?:CODE)?[ \t]*:[ \t]*([A-Z0-9]{2})(?![A-Z0-9])").matcher(raw);
        if (iata.find()) {
            String resolved = new PermitOperatorCatalog().operatorForIata(iata.group(1));
            if (resolved != null) return resolved;
        }
        return "UNKNOWN";
    }

    private void writeWorkbook(Path target, JsonNode manifest, List<DocxPermitFormatProfile> profiles,
                               List<Map<String, Object>> results) throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            Sheet method = sheet(workbook, "Method", "Item", "Definition");
            add(method, "Period inclusive", manifest.path("start").asText() + " through " + manifest.path("end").asText());
            add(method, "Snapshot", manifest.path("snapshot").asText());
            add(method, "Storage", manifest.path("root").asText());
            add(method, "Scope", "All .doc/.docx including hashed suffixes across incoming/processed/error/quarantine/error-format; Word lock files excluded");
            add(method, "Date", "Attachment folder for email-attachments; otherwise outer ingest filename > status folder > mtime fallback. Proxies, NOT verified EmailMetadata.receivedAt; inspect Aliases sheet.");
            add(method, "Physical files", manifest.path("physicalFiles").asInt()); add(method, "Unique SHA-256", results.size());
            add(method, "Readable", "Detect + parse + normalize + source-field validation. CRAFT_ID and unresolved 3-letter IATA airport lookups excluded as catalog resolution; not import-ready. ReviewOnly shown separately.");
            add(method, "Rate", "READABLE / (all unique samples - confirmed NON_PERMIT); uncertain/read/field failures remain in denominator. No samples = N/A.");
            add(method, "Resolution and import", "NOT_TESTED: offline only, no DB access/replay. Missing aircraft/A000 are NOT resolution success.");
            add(method, "Limits", "No reconciliation with live baseline, purpose/aircraft/route catalog resolution, or saved-job import check; parse results do not prove all fields match the Word source.");
            add(method, "Inventory errors", manifest.path("inventoryErrors").toString());
            Sheet profileSheet = sheet(workbook, "Profiles", "Profile", "Carrier", "Priority", "Samples", "Readable", "Rate", "Review only", "Source");
            Map<String, List<Map<String, Object>>> airlines = new TreeMap<>();
            for (DocxPermitFormatProfile profile : profiles) {
                String operator = profile.operator() == null ? "" : Objects.toString(profile.operator().fixedValue(), "");
                if (!operator.isBlank()) airlines.computeIfAbsent(operator, key -> new ArrayList<>());
                var samples = results.stream().filter(r -> profile.id().equals(r.get("profile")) && !"NON_PERMIT".equals(r.get("status"))).toList();
                long ok = samples.stream().filter(r -> "READABLE".equals(r.get("status"))).count();
                add(profileSheet, profile.id(), operator.isBlank() ? "dynamic/shared" : operator, profile.priority(), samples.size(), ok,
                        samples.isEmpty() ? "N/A" : (double) ok / samples.size(), profile.validation().reviewOnly(), "permit-formats/" + profile.id() + ".yaml");
            }
            results.forEach(r -> airlines.computeIfAbsent(Objects.toString(r.get("carrier"), "UNKNOWN"), key -> new ArrayList<>()).add(r));
            Sheet airlineSheet = sheet(workbook, "Airlines", "ICAO", "Dedicated profiles", "Unique samples", "Non-permit", "Denominator", "Readable", "Rate", "Failures", "Resolution", "Import");
            for (var entry : airlines.entrySet()) {
                var samples = entry.getValue();
                long non = samples.stream().filter(r -> "NON_PERMIT".equals(r.get("status"))).count();
                long ok = samples.stream().filter(r -> "READABLE".equals(r.get("status"))).count();
                long denominator = samples.size() - non;
                long dedicated = profiles.stream().filter(p -> p.operator() != null && entry.getKey().equals(p.operator().fixedValue())).count();
                add(airlineSheet, entry.getKey(), dedicated, samples.size(), non, denominator, ok,
                        denominator == 0 ? "N/A" : (double) ok / denominator, denominator - ok, "NOT_TESTED", "NOT_TESTED");
            }
            Sheet files = sheet(workbook, "Files", "SHA256", "Source", "ICAO", "Profile", "Status", "Permit", "NEW rows", "OLD rows", "Aircraft", "Purposes", "Review only", "Error", "Resolution", "Import", "Pending catalog fields");
            Sheet errors = sheet(workbook, "Errors", "SHA256", "Source", "Status", "Details");
            Sheet aliases = sheet(workbook, "Aliases", "SHA256", "Source", "Date", "Date basis", "State");
            for (var r : results) {
                add(files, r.get("sha256"), r.get("path"), r.get("carrier"), r.get("profile"), r.get("status"), r.get("permit"), r.get("flights"),
                        r.get("oldFlights"), r.get("aircraft"), r.get("purposes"), r.get("reviewOnly"), r.get("error"), r.get("resolution"), r.get("import"), r.get("pendingCatalogFields"));
                if (!"READABLE".equals(r.get("status"))) add(errors, r.get("sha256"), r.get("path"), r.get("status"), r.get("error"));
                for (JsonNode alias : (JsonNode) r.get("aliases")) add(aliases, r.get("sha256"), alias.path("path").asText(), alias.path("date").asText(), alias.path("dateBasis").asText(), alias.path("state").asText());
            }
            CellStyle header = workbook.createCellStyle(); Font bold = workbook.createFont(); bold.setBold(true); header.setFont(bold);
            CellStyle percent = workbook.createCellStyle(); percent.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));
            for (Sheet sheet : workbook) {
                sheet.createFreezePane(0, 1);
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, sheet.getLastRowNum(), 0, sheet.getRow(0).getLastCellNum() - 1));
                for (Cell cell : sheet.getRow(0)) {
                    cell.setCellStyle(header); sheet.setColumnWidth(cell.getColumnIndex(), cell.getColumnIndex() == 1 ? 72 * 256 : 26 * 256);
                    if ("Rate".equals(cell.getStringCellValue())) for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                        Cell data = sheet.getRow(i).getCell(cell.getColumnIndex()); if (data.getCellType() == CellType.NUMERIC) data.setCellStyle(percent);
                    }
                }
            }
            try (var stream = Files.newOutputStream(target)) { workbook.write(stream); }
        }
    }

    private Sheet sheet(Workbook workbook, String name, String... headers) {
        Sheet sheet = workbook.createSheet(name); add(sheet, (Object[]) headers); return sheet;
    }
    private void add(Sheet sheet, Object... values) {
        Row row = sheet.createRow(sheet.getPhysicalNumberOfRows());
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i); Object value = values[i];
            if (value instanceof Number n) cell.setCellValue(n.doubleValue());
            else { String text = Objects.toString(value, ""); cell.setCellValue(text.substring(0, Math.min(text.length(), 32767))); }
        }
    }
}
