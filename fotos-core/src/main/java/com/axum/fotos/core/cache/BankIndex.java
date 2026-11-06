package com.axum.fotos.core.cache;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.pick.Candidate;
import com.axum.fotos.core.sheet.CsvCell;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code indice.csv} in the root of the bank: one line per EAN, in EAN order, with where its photo came from. It is
 * what gets versioned (the photos do not), so it is written in a stable order and only changes when the bank does.
 * Rewritten whole at the end of every run, atomically.
 */
public final class BankIndex {

    public static final String FILE_NAME = "indice.csv";

    private BankIndex() {}

    /** @return how many lines (EAN) the index has */
    public static int write(LocalCache bank) throws IOException {
        var sb = new StringBuilder("EAN,Estado,Origen,Tienda,Tipo,Formato,Lado_px,Original,Fecha,Pagina,Imagen,Detalle\r\n");
        int lines = 0;
        for (Ean ean : bank.eans()) {
            var found = bank.find(ean);
            if (found.isEmpty()) continue;
            CacheEntry e = found.get();
            boolean ready = e.status() == CacheEntry.Status.READY;
            sb.append(CsvCell.of(ean.value(), false)).append(',')
                    .append(e.status().name()).append(',')
                    .append(ready ? (e.source() == CacheEntry.Source.MANUAL ? "propia" : "tienda") : "").append(',')
                    .append(CsvCell.of(ready ? e.store() : "", true)).append(',')
                    .append(ready ? (e.kind() == Candidate.Kind.CROPPED ? "recortada" : "original") : "").append(',')
                    .append(ready ? e.format().extension() : "").append(',')
                    .append(ready ? Integer.toString(e.side()) : "").append(',')
                    .append(ready ? (e.hasOriginal() ? "si" : "no") : "").append(',')
                    .append(CsvCell.of(e.fetchedAt().toString(), false)).append(',')
                    .append(CsvCell.of(e.pageUrl(), true)).append(',')
                    .append(CsvCell.of(e.imageUrl(), true)).append(',')
                    .append(CsvCell.of(e.detail(), true)).append("\r\n");
            lines++;
        }
        Files.createDirectories(bank.root());
        Path tmp = bank.root().resolve(FILE_NAME + ".tmp");
        Files.writeString(tmp, "﻿" + sb, StandardCharsets.UTF_8);
        Files.move(tmp, bank.root().resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return lines;
    }
}
