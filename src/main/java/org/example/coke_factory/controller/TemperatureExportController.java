package org.example.coke_factory.controller;

import lombok.RequiredArgsConstructor;
import org.example.coke_factory.entity.OvenTemperatureReading;
import org.example.coke_factory.repository.OvenTemperatureReadingRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/temperature/export")
@RequiredArgsConstructor
public class TemperatureExportController {
    private final OvenTemperatureReadingRepository repository;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private List<OvenTemperatureReading> select(LocalDateTime from, LocalDateTime to, Integer ovenNumber) {
        return repository.findAllByOrderByRecordedAtAsc().stream()
                .filter(r -> from == null || !r.getRecordedAt().isBefore(from))
                .filter(r -> to == null || !r.getRecordedAt().isAfter(to))
                .filter(r -> ovenNumber == null || r.getOven().getNameOven().endsWith("-" + ovenNumber))
                .toList();
    }

    @GetMapping(value = "/csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Integer ovenNumber) {
        StringBuilder out = new StringBuilder("\uFEFFПечь;Дата и время;Температура, °C\r\n");
        for (var r : select(from, to, ovenNumber)) {
            out.append(csvCell(r.getOven().getNameOven())).append(';')
                    .append(r.getRecordedAt().format(DATE)).append(';')
                    .append(r.getTemperature()).append("\r\n");
        }
        return download(out.toString().getBytes(StandardCharsets.UTF_8), "temperature-analytics.csv", "text/csv; charset=UTF-8");
    }

    @GetMapping(value = "/xlsx")
    public ResponseEntity<byte[]> xlsx(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Integer ovenNumber) throws IOException {
        var rows = select(from, to, ovenNumber);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>
                    """);
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
                    """);
            entry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Температура печей" sheetId="1" r:id="rId1"/></sheets></workbook>
                    """);
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>
                    """);
            StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            sheet.append("<row r=\"1\">").append(cell("A1", "Печь")).append(cell("B1", "Дата и время")).append(cell("C1", "Температура, °C")).append("</row>");
            int n = 2;
            for (var r : rows) {
                sheet.append("<row r=\"").append(n).append("\">")
                        .append(cell("A" + n, r.getOven().getNameOven()))
                        .append(cell("B" + n, r.getRecordedAt().format(DATE)))
                        .append(numberCell("C" + n, r.getTemperature())).append("</row>");
                n++;
            }
            sheet.append("</sheetData></worksheet>");
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return download(bytes.toByteArray(), "temperature-analytics.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private static String csvCell(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }
    private static String xml(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private static String cell(String ref, String value) { return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + xml(value) + "</t></is></c>"; }
    private static String numberCell(String ref, Double value) { return "<c r=\"" + ref + "\"><v>" + (value == null ? "0" : value) + "</v></c>"; }
    private static void entry(ZipOutputStream zip, String name, String content) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private static ResponseEntity<byte[]> download(byte[] bytes, String name, String type) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(type)).body(bytes);
    }
}
