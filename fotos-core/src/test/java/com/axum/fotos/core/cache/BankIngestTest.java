package com.axum.fotos.core.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.cache.BankIngest.Report;
import com.axum.fotos.core.image.ImageCodec;
import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The photos a person drops in the bank: recognised by their name, checked like anything that comes from outside. */
class BankIngestTest {

    private static final String EAN = "7791337010604";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T15:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private LocalCache bank;

    @BeforeEach
    void open() {
        bank = new LocalCache(dir.resolve("banco"));
    }

    private static byte[] picture(ImageFormat format, int w, int h) throws IOException {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(60, 140, 70));
        g.fillRect(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        return ImageCodec.encode(image, format);
    }

    private Path drop(String name, byte[] bytes) throws IOException {
        Files.createDirectories(bank.root());
        return Files.write(bank.root().resolve(name), bytes);
    }

    private Report run() throws IOException {
        return BankIngest.ingest(bank, CLOCK);
    }

    @Test
    void aJpegNamedWithTheEanBecomesThePhotoOfThatEanAndLeavesTheRoot() throws IOException {
        byte[] jpeg = picture(ImageFormat.JPEG, 800, 600);
        Path dropped = drop(EAN + ".jpg", jpeg);

        var report = run();

        assertEquals(List.of(Ean.parse(EAN)), report.ingested());
        assertTrue(report.skipped().isEmpty());
        var entry = bank.find(Ean.parse(EAN)).orElseThrow();
        assertEquals(CacheEntry.Source.MANUAL, entry.source());
        assertEquals(Candidate.OWN_STORE, entry.store());
        assertEquals(ImageFormat.JPEG, entry.format());
        assertEquals(600, entry.side(), "the shorter side");
        assertArrayEquals(jpeg, bank.readImage(Ean.parse(EAN), ImageFormat.JPEG), "stored exactly as dropped, not re-encoded");
        assertFalse(Files.exists(dropped), "taken out of the root once it is saved");
    }

    @Test
    void aPngIsRecognisedToo() throws IOException {
        drop(EAN + ".png", picture(ImageFormat.PNG, 700, 700));

        run();

        assertEquals(ImageFormat.PNG, bank.find(Ean.parse(EAN)).orElseThrow().format());
    }

    @Test
    void theExtensionAndTheCaseDoNotMatterButTheNameIsOnlyTheEan() throws IOException {
        drop(EAN + ".JPEG", picture(ImageFormat.JPEG, 600, 600));
        drop("7791337010710.Jpg", picture(ImageFormat.JPEG, 600, 600));

        var report = run();

        assertEquals(2, report.ingested().size());
    }

    /** The type comes from the first bytes: a PNG saved with a .jpg name is stored as the PNG it is. */
    @Test
    void theRealTypeIsDecidedByTheBytesNotByTheExtension() throws IOException {
        drop(EAN + ".jpg", picture(ImageFormat.PNG, 600, 600));

        run();

        var entry = bank.find(Ean.parse(EAN)).orElseThrow();
        assertEquals(ImageFormat.PNG, entry.format());
        assertTrue(Files.exists(bank.root().resolve(EAN).resolve("image.png")));
    }

    @Test
    void aNameThatIsNotAnEanIsLeftWhereItIsAndReportedWithWhy() throws IOException {
        Path sku = drop("SKU-640008.jpg", picture(ImageFormat.JPEG, 600, 600));
        Path wrongDigit = drop("7791337010605.jpg", picture(ImageFormat.JPEG, 600, 600));
        Path twoEans = drop(EAN + " (1).jpg", picture(ImageFormat.JPEG, 600, 600));

        var report = run();

        assertTrue(report.ingested().isEmpty());
        assertEquals(3, report.skipped().size());
        assertTrue(report.skipped().stream().allMatch(s -> s.reason().contains("EAN")), report.skipped().toString());
        assertTrue(Files.exists(sku) && Files.exists(wrongDigit) && Files.exists(twoEans), "nothing is deleted that was not saved");
        assertTrue(bank.eans().isEmpty());
    }

    /** A name is never used to build a path: only the validated EAN is, so this cannot write outside the bank. */
    @Test
    void aNameThatTriesToEscapeTheBankIsJustNotAnEan() throws IOException {
        drop("..%2F..%2Fescape.jpg", picture(ImageFormat.JPEG, 600, 600));
        drop("..jpg", picture(ImageFormat.JPEG, 600, 600));

        var report = run();

        assertTrue(report.ingested().isEmpty());
        assertFalse(Files.exists(dir.resolve("escape")), "nothing was written next to the bank");
        assertEquals(List.of(), bank.eans());
    }

    @Test
    void aFileThatIsNotAnImageAtAllIsReportedAndKept() throws IOException {
        Path notes = drop("notas.txt", "hola".getBytes(StandardCharsets.UTF_8));
        Path fake = drop(EAN + ".jpg", "<html>no soy una imagen</html>".getBytes(StandardCharsets.UTF_8));

        var report = run();

        assertTrue(report.ingested().isEmpty());
        assertEquals(2, report.skipped().size());
        assertTrue(Files.exists(notes) && Files.exists(fake));
        assertTrue(bank.find(Ean.parse(EAN)).isEmpty(), "a fake image never becomes the photo of an EAN");
    }

    @Test
    void aWebpIsRefusedWithAReasonThatSaysWhatToDo() throws IOException {
        drop(EAN + ".webp", "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1));

        var report = run();

        assertEquals(1, report.skipped().size());
        assertTrue(report.skipped().get(0).reason().contains("JPG o PNG"), report.skipped().get(0).reason());
    }

    /** A tiny file can declare a canvas of hundreds of megapixels: it is refused before the decoder allocates it. */
    @Test
    void aFileDeclaringAHugeCanvasIsRefusedAndKept() throws IOException {
        byte[] patched = picture(ImageFormat.PNG, 4, 4).clone();
        ByteBuffer.wrap(patched).putInt(16, 20_000).putInt(20, 20_000);
        var crc = new CRC32();
        crc.update(patched, 12, 17);
        ByteBuffer.wrap(patched).putInt(29, (int) crc.getValue());
        Path dropped = drop(EAN + ".png", patched);

        var report = run();

        assertTrue(report.ingested().isEmpty());
        assertTrue(Files.exists(dropped));
    }

    @Test
    void theBanksOwnFilesAreNeverReportedAsNotBeingPhotos() throws IOException {
        drop("indice.csv", "EAN\n".getBytes(StandardCharsets.UTF_8));
        drop("README.md", "x".getBytes(StandardCharsets.UTF_8));
        drop(".gitignore", "*".getBytes(StandardCharsets.UTF_8));
        drop("Thumbs.db", new byte[]{1});
        drop("desktop.ini", new byte[]{1});
        drop("indice.csv.tmp", new byte[]{1});

        var report = run();

        assertTrue(report.ingested().isEmpty());
        assertTrue(report.skipped().isEmpty(), report.skipped().toString());
    }

    @Test
    void aSecondRunDoesNotDoItAgain() throws IOException {
        drop(EAN + ".jpg", picture(ImageFormat.JPEG, 600, 600));

        run();
        var second = run();

        assertTrue(second.ingested().isEmpty() && second.skipped().isEmpty());
    }

    /** The point of it: a store's photo that was bad is replaced by yours, and it is kept as the previous one. */
    @Test
    void aPhotoDroppedForAnEanThatAlreadyHasOneReplacesItAndKeepsTheOldAsPrevious() throws IOException {
        var fromStore = new Candidate("jumbo", "https://page", "https://img", Candidate.Kind.CROPPED, ImageFormat.JPEG, 900, 0.0, 200.0,
                new byte[]{1, 2, 3}, 0L, new byte[]{9, 9});
        bank.saveReady(Ean.parse(EAN), fromStore, CLOCK.instant());
        byte[] mine = picture(ImageFormat.JPEG, 640, 640);
        drop(EAN + ".jpg", mine);

        run();

        var entry = bank.find(Ean.parse(EAN)).orElseThrow();
        assertEquals(CacheEntry.Source.MANUAL, entry.source());
        assertArrayEquals(mine, bank.readImage(Ean.parse(EAN), ImageFormat.JPEG));
        assertFalse(entry.hasOriginal(), "the old crop's original does not describe your photo");
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(bank.root().resolve(EAN).resolve("previa.jpg")));
    }

    @Test
    void anEanThatHadNoPhotoGetsOne() throws IOException {
        bank.saveEmpty(Ean.parse(EAN), CacheEntry.Status.NOT_FOUND, "Ninguna tienda lo vende.", CLOCK.instant());
        drop(EAN + ".jpg", picture(ImageFormat.JPEG, 600, 600));

        run();

        assertEquals(CacheEntry.Status.READY, bank.find(Ean.parse(EAN)).orElseThrow().status());
    }

    @Test
    void aMissingBankFolderIsNotAnError() throws IOException {
        var report = run();

        assertTrue(report.ingested().isEmpty() && report.skipped().isEmpty());
    }
}
