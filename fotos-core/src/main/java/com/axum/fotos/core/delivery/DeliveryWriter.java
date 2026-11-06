package com.axum.fotos.core.delivery;

import com.axum.fotos.core.delivery.DeliveryLine.Result;
import com.axum.fotos.core.pick.Candidate;
import com.axum.fotos.core.pick.Warning;
import com.axum.fotos.core.sheet.CsvCell;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes what the person hands to the store:
 *
 * <pre>
 *   fotos/            one photo per SKU, named &lt;SKU&gt;.jpg or &lt;SKU&gt;.png (what the photo really is, never converted)
 *   fotos.zip         the same photos, ready to send
 *   reporte.csv       one line per row of the sheet: what was delivered, from where, and what to look at
 *   galeria.html      the same, to look at
 * </pre>
 *
 * <p>Changing a photo is replacing its file in {@code fotos/}; the zip is then stale and has to be made again.
 * A folder that already exists is never reused (see {@link #newFolder}): a rerun must not overwrite photos
 * that someone already swapped by hand.
 *
 * <p>The report and the gallery are for the person, so their text is Spanish. Free text that came from the client's
 * sheet is escaped for HTML and made harmless for a spreadsheet program (a cell starting with = would be a formula).
 */
public final class DeliveryWriter {

    public record Written(Path folder, Path photos, Path zip, Path report, Path gallery, int photoCount,
                          List<DeliveryLine> lines) {}

    private DeliveryWriter() {}

    /**
     * Creates {@code root/name}, or {@code root/name (2)} and so on if it already exists, and the root itself if it
     * is missing. The name is chosen by the caller (the tool uses "Fotos &lt;date&gt;"), never taken from the client's sheet.
     */
    public static Path newFolder(Path root, String name) throws IOException {
        Files.createDirectories(root);
        for (int n = 1; ; n++) {
            Path candidate = root.resolve(n == 1 ? name : name + " (" + n + ")");
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException taken) {
                // Try the next number.
            }
        }
    }

    public static Written write(Path folder, List<DeliveryLine> lines) throws IOException {
        List<DeliveryLine> finals = resolveFileNames(lines);
        Path photos = Files.createDirectories(folder.resolve("fotos"));

        Set<String> written = new HashSet<>();
        var names = new ArrayList<String>();
        for (DeliveryLine line : finals) {
            if (line.result() != Result.OK) continue;
            String name = fileName(line);
            if (!written.add(name.toLowerCase(Locale.ROOT))) continue;
            Files.write(photos.resolve(name), line.image());
            names.add(name);
        }

        Path zip = folder.resolve("fotos.zip");
        try (OutputStream out = Files.newOutputStream(zip); var z = new ZipOutputStream(out)) {
            for (String name : names) {
                z.putNextEntry(new ZipEntry(name));
                z.write(Files.readAllBytes(photos.resolve(name)));
                z.closeEntry();
            }
        }

        Path report = folder.resolve("reporte.csv");
        Files.writeString(report, "﻿" + report(finals), StandardCharsets.UTF_8);
        Path gallery = folder.resolve("galeria.html");
        Files.writeString(gallery, gallery(finals), StandardCharsets.UTF_8);
        return new Written(folder, photos, zip, report, gallery, names.size(), finals);
    }

    /**
     * The last line of defence for a caller that builds lines by hand: {@link RowChecker} already keeps such rows out of
     * the batch run, but a photo must never be written under a name that is not a file name or over another product.
     */
    private static List<DeliveryLine> resolveFileNames(List<DeliveryLine> lines) {
        Map<String, DeliveryLine> owner = new HashMap<>();
        var out = new ArrayList<DeliveryLine>();
        for (DeliveryLine line : lines) {
            if (line.result() != Result.OK) {
                out.add(line);
                continue;
            }
            if (SkuFileName.of(line.sku()).isEmpty()) {
                out.add(DeliveryLine.invalid(line.row(), line.sku(), line.ean(), line.description(), RowProblem.BAD_SKU,
                        RowProblem.BAD_SKU.message()));
                continue;
            }
            String key = line.sku().trim().toLowerCase(Locale.ROOT);
            DeliveryLine first = owner.putIfAbsent(key, line);
            if (first != null && !first.ean().equals(line.ean())) {
                out.add(DeliveryLine.invalid(line.row(), line.sku(), line.ean(), line.description(), RowProblem.SKU_REUSED,
                        RowProblem.skuReused(first.row())));
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private static String fileName(DeliveryLine line) {
        return line.sku().trim() + "." + line.format().extension();
    }

    static String warningText(Warning warning) {
        return switch (warning) {
            case TOUCHES_EDGE -> "El producto toca el borde de la foto: mirar si está cortado.";
            case SOFT -> "La foto se ve blanda (puede ser una miniatura agrandada).";
            case LOW_RESOLUTION -> "Resolución baja (menos de " + Warning.MIN_SIDE + " px).";
        };
    }

    private static String report(List<DeliveryLine> lines) {
        var sb = new StringBuilder("Fila,SKU,EAN,Descripcion,Estado,Tienda,Tipo,Lado_px,Avisos,Detalle\r\n");
        for (DeliveryLine l : lines) {
            String warnings = l.warnings().stream().map(DeliveryWriter::warningText).collect(Collectors.joining(" "));
            sb.append(l.row()).append(',')
                    .append(CsvCell.of(l.sku(), false)).append(',')
                    .append(CsvCell.of(l.ean(), false)).append(',')
                    .append(CsvCell.of(l.description(), true)).append(',')
                    .append(l.stateName()).append(',')
                    .append(CsvCell.of(l.store(), true)).append(',')
                    .append(l.kind() == null ? "" : l.kind() == Candidate.Kind.ORIGINAL ? "original" : "recortada").append(',')
                    .append(l.side() == 0 ? "" : Integer.toString(l.side())).append(',')
                    .append(CsvCell.of(warnings, true)).append(',')
                    .append(CsvCell.of(l.detail(), true)).append("\r\n");
        }
        return sb.toString();
    }

    private static String gallery(List<DeliveryLine> lines) {
        long ignored = lines.stream().filter(l -> l.result() == Result.DATOS_INVALIDOS).count();
        long shown = lines.size() - ignored;
        // Derived from the kinds that are really there, not a list typed by hand that falls behind RowProblem.
        String kinds = lines.stream().filter(l -> l.result() == Result.DATOS_INVALIDOS && l.problem() != null)
                .map(l -> l.problem().summary()).distinct().collect(Collectors.joining(", "));
        long ok = lines.stream().filter(l -> l.result() == Result.OK).count();
        long withWarnings = lines.stream().filter(l -> l.result() == Result.OK && !l.warnings().isEmpty()).count();
        var cards = new StringBuilder();
        for (DeliveryLine l : lines) {
            // Rows with bad data are ignored here: they are only counted above and explained in reporte.csv.
            if (l.result() == Result.DATOS_INVALIDOS) continue;
            String label = esc(l.description()) + "<br>SKU " + esc(l.sku()) + " · " + esc(l.ean());
            if (l.result() == Result.OK) {
                String note = l.warnings().isEmpty() ? "<p class=\"tag green\">Sin avisos</p>"
                        : l.warnings().stream().map(w -> "<p class=\"tag amber\">" + esc(warningText(w)) + "</p>").collect(Collectors.joining());
                cards.append("<article><img loading=\"lazy\" src=\"fotos/").append(esc(fileName(l))).append("\" alt=\"\"><h3>")
                        .append(label).append("</h3><p>").append(esc(l.store())).append(" · ").append(l.side()).append(" px · ")
                        .append(l.kind() == Candidate.Kind.ORIGINAL ? "original" : "banner recortado").append("</p>").append(note).append("</article>\n");
            } else {
                String why = l.result() == Result.ERROR ? "No se pudo consultar: volver a correr" : "Sin imagen";
                cards.append("<article class=\"none\"><div class=\"ph\">").append(esc(why)).append("</div><h3>").append(label)
                        .append("</h3><p class=\"tag red\">").append(esc(l.detail())).append("</p></article>\n");
            }
        }
        return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><title>Fotos entregadas</title>\n<style>\n"
                + "body{font:14px/1.4 system-ui,sans-serif;margin:0;padding:20px;background:#eef0ec;color:#3d4438}\n"
                + "h1{margin:0 0 4px;font-size:20px;color:#1b2016}.sum{margin:0 0 18px;color:#636b5c}\n"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px}\n"
                + "article{background:#fff;border:1px solid #ccd3c4;border-radius:12px;padding:10px;display:flex;flex-direction:column;gap:4px}\n"
                + "article img,.ph{width:100%;aspect-ratio:1/1;object-fit:contain;background:#fff;border:1px solid #e3e7dd;border-radius:8px}\n"
                + ".ph{display:flex;align-items:center;justify-content:center;text-align:center;color:#b3261e;background:#fdecea}\n"
                + "h3{margin:4px 0 0;font-size:12px;color:#1b2016;font-weight:600}article p{margin:0;font-size:11px;color:#636b5c}\n"
                + ".tag{align-self:flex-start;padding:1px 8px;border-radius:999px;border:1px solid currentColor;font-weight:600}\n"
                + ".green{color:#2f6b2f}.amber{color:#8a5a00}.red{color:#b3261e}\n</style></head><body>\n"
                + "<h1>" + shown + " productos consultados</h1>\n<p class=\"sum\">" + ok + " con foto ("
                + withWarnings + " con avisos para mirar), " + (shown - ok) + " sin foto."
                + (ignored == 0 ? "" : " Se ignoraron " + ignored + " filas de la planilla con datos que corregir"
                + (kinds.isEmpty() ? "" : " (" + esc(kinds) + ")") + ": están explicadas en <b>reporte.csv</b>.")
                + " Cada archivo de la carpeta "
                + "<b>fotos/</b> se llama como su SKU: para cambiar una foto, reemplazá el archivo (y volvé a armar el zip).</p>\n"
                + "<div class=\"grid\">\n" + cards + "</div></body></html>\n";
    }

    private static String esc(Object value) {
        return String.valueOf(value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
