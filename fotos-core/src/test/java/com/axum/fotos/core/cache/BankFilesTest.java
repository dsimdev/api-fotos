package com.axum.fotos.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankFilesTest {

    @TempDir
    Path dir;

    @Test
    void aNewBankGetsAReadmeAndAGitignoreThatVersionsOnlyTheIndex() throws IOException {
        Path bank = dir.resolve("banco");

        BankFiles.ensure(bank);

        String ignore = Files.readString(bank.resolve(".gitignore"), StandardCharsets.UTF_8);
        assertTrue(ignore.contains("*\n") && ignore.contains("!indice.csv") && ignore.contains("!README.md") && ignore.contains("!.gitignore"), ignore);
        assertTrue(Files.readString(bank.resolve("README.md"), StandardCharsets.UTF_8).contains("<EAN>.jpg"));
    }

    @Test
    void aPersonsEditsAreNeverOverwritten() throws IOException {
        Path bank = Files.createDirectories(dir.resolve("banco"));
        Files.writeString(bank.resolve("README.md"), "mi texto");
        Files.writeString(bank.resolve(".gitignore"), "lo mio");

        BankFiles.ensure(bank);

        assertEquals("mi texto", Files.readString(bank.resolve("README.md")));
        assertEquals("lo mio", Files.readString(bank.resolve(".gitignore")));
    }

    @Test
    void theBanksOwnFilesAreNotTakenForDroppedPhotos() throws IOException {
        Path bank = dir.resolve("banco");
        BankFiles.ensure(bank);

        var report = BankIngest.ingest(new LocalCache(bank), java.time.Clock.systemUTC());

        assertTrue(report.ingested().isEmpty());
        assertFalse(report.skipped().stream().anyMatch(s -> s.file().equals("README.md") || s.file().equals(".gitignore")), report.skipped().toString());
    }
}
