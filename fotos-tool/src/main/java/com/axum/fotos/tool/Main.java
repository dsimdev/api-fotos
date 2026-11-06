package com.axum.fotos.tool;

import com.axum.fotos.core.cache.BankFiles;
import com.axum.fotos.core.cache.BankGit;
import com.axum.fotos.core.cache.BankIndex;
import com.axum.fotos.core.cache.BankIngest;
import com.axum.fotos.core.cache.CacheEntry;
import com.axum.fotos.core.cache.LocalCache;
import com.axum.fotos.core.delivery.DeliveryLine;
import com.axum.fotos.core.delivery.DeliveryLine.Result;
import com.axum.fotos.core.delivery.DeliveryWriter;
import com.axum.fotos.core.delivery.RowChecker;
import com.axum.fotos.core.delivery.RowProblem;
import com.axum.fotos.core.sheet.ProductRow;
import com.axum.fotos.core.sheet.SheetFormatException;
import com.axum.fotos.core.sheet.SpreadsheetReader;
import java.awt.Desktop;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The way a person uses this: drop a product list (xlsx or csv with SKU and EAN) on the launcher and get a folder
 * with one photo per SKU. Everything it prints is for that person, so it is in Spanish.
 *
 * <pre>
 *   fotos-tool &lt;planilla&gt; [--refresh] [--cache &lt;carpeta&gt;] [--salida &lt;carpeta&gt;] [--paralelo &lt;1-8&gt;] [--no-abrir]
 *   fotos-tool --banco [--cache &lt;carpeta&gt;] [--sin-git]
 * </pre>
 *
 * <p>Each run writes a new folder {@code Fotos <date>} INSIDE the folder the program is installed in (the sheet is
 * usually dropped from Downloads, and that is not where the deliveries should pile up). {@code --salida} puts them
 * elsewhere. Run from a build and not from an install, with no {@code --salida}, it writes next to the sheet.
 *
 * <p>The photo BANK ({@code banco-fotos} inside the program folder, by EAN) lives outside the delivery folder so that
 * the next list reuses every photo already found. A photo a person drops in the bank as {@code <EAN>.jpg} is
 * recognised at the start of every run, and {@code --banco} does only that and rewrites the index.
 * If the bank folder is a git repository, a changed index is committed and pushed after the run ({@code --sin-git} skips it).
 * {@code --refresh} asks the stores again for the EANs of THIS list and replaces a stored photo only with a better
 * one found: it never throws a stored photo away.
 */
public final class Main {

    /** Builds the finder; replaceable so the whole run can be tested without the network. */
    @FunctionalInterface
    interface FinderFactory {
        PhotoFinder create(Path cacheDir, ExecutorService pool) throws IOException;
    }

    /** Products looked up at the same time unless the person says otherwise. */
    static final int DEFAULT_PARALLEL = 3;
    static final int MAX_PARALLEL = 8;

    private Main() {}

    public static void main(String[] args) {
        String version = Main.class.getPackage().getImplementationVersion();
        String v = version == null ? "dev" : version;
        int code = run(args, System.out, (cache, pool) -> ToolWiring.finder(v, cache, pool), Main::openFolder,
                installDir(), Clock.system(ZoneId.of("America/Argentina/Buenos_Aires")));
        System.exit(code);
    }

    /**
     * @param installDir the folder the program is installed in, where the deliveries go by default; null when the tool
     *                   is not running from an install
     * @param clock      gives the date in the name of the delivery folder
     */
    static int run(String[] args, PrintStream out, FinderFactory factory, Consumer<Path> opener, Path installDir, Clock clock) {
        return run(args, out, factory, opener, installDir, clock, BankGit.SYSTEM);
    }

    /** @param git how the bank's index is committed and pushed; replaceable so a test needs neither git nor a network */
    static int run(String[] args, PrintStream out, FinderFactory factory, Consumer<Path> opener, Path installDir, Clock clock,
                   BankGit.Runner git) {
        Path sheet = null;
        Path cacheDir = null;
        Path outputRoot = null;
        int parallel = DEFAULT_PARALLEL;
        boolean refresh = false, open = true, bankOnly = false, noGit = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--refresh" -> refresh = true;
                case "--no-abrir" -> open = false;
                case "--banco" -> bankOnly = true;
                case "--sin-git" -> noGit = true;
                case "--cache" -> {
                    if (i + 1 >= args.length) return fail(out, "Falta la carpeta después de --cache.");
                    cacheDir = Path.of(args[++i]);
                }
                case "--paralelo" -> {
                    if (i + 1 >= args.length) return fail(out, "Falta el número después de --paralelo.");
                    try {
                        parallel = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        return fail(out, "Después de --paralelo va un número de 1 a " + MAX_PARALLEL + ".");
                    }
                    if (parallel < 1 || parallel > MAX_PARALLEL) return fail(out, "--paralelo va de 1 a " + MAX_PARALLEL + ".");
                }
                case "--salida" -> {
                    if (i + 1 >= args.length) return fail(out, "Falta la carpeta después de --salida.");
                    outputRoot = Path.of(args[++i]);
                }
                case "--help", "-h", "ayuda" -> {
                    usage(out);
                    return 0;
                }
                default -> {
                    if (args[i].startsWith("--")) return fail(out, "No conozco la opción " + args[i] + ".");
                    if (sheet != null) return fail(out, "Pasé más de una planilla: arrastrá una sola por vez.");
                    sheet = Path.of(args[i]);
                }
            }
        }
        if (bankOnly) {
            if (sheet != null) return fail(out, "--banco no lleva planilla: solo reconoce las fotos propias y arma el índice.");
            return tendBank(out, new LocalCache(cacheDir != null ? cacheDir : defaultBankDir(installDir)), clock, noGit, git);
        }
        if (sheet == null) {
            usage(out);
            return 1;
        }
        if (!Files.isRegularFile(sheet)) return fail(out, "No encuentro la planilla: " + sheet);

        List<ProductRow> rows;
        try {
            rows = SpreadsheetReader.read(sheet);
        } catch (SheetFormatException e) {
            return fail(out, e.getMessage());
        } catch (IOException e) {
            return fail(out, "No pude leer la planilla: " + e.getMessage());
        }
        if (rows.isEmpty()) return fail(out, "La planilla no tiene filas con SKU y EAN.");

        if (cacheDir == null) cacheDir = defaultBankDir(installDir);
        if (outputRoot == null) outputRoot = installDir != null ? installDir : sheet.toAbsolutePath().getParent();
        // Each product asks every store at once, so the threads scale with the number of stores AND with how many
        // products run together (a fixed number would silently queue the lookups once there are more stores).
        ExecutorService pool = Executors.newFixedThreadPool(storeThreads() * parallel);
        try {
            long started = System.nanoTime();
            long ignoredRows = RowChecker.check(rows).stream().filter(c -> !c.usable()).count();
            out.println("Planilla: " + sheet.getFileName() + " (" + rows.size() + " filas: " + (rows.size() - ignoredRows)
                    + " para consultar" + (ignoredRows > 0 ? " y " + ignoredRows + " que se ignoran por tener datos que corregir" : "") + ")");
            out.println("Banco de fotos: " + cacheDir);
            out.println("Las entregas se guardan en: " + outputRoot);
            out.println("Productos que se consultan a la vez: " + parallel);
            out.println(refresh ? "Se vuelve a consultar todo (--refresh); una foto guardada solo se cambia si se encuentra otra." : "Lo que ya se bajó antes sale del banco.");
            out.println();

            LocalCache bank = new LocalCache(cacheDir);
            takeDroppedPhotos(out, bank, clock);

            PhotoFinder finder = factory.create(cacheDir, pool);
            List<DeliveryLine> lines = BatchRun.run(rows, finder, refresh, parallel, (done, total, line, fromCache) ->
                    out.println(progressLine(done, total, line, fromCache)));

            Path folder = DeliveryWriter.newFolder(outputRoot, "Fotos " + LocalDate.now(clock));
            var written = DeliveryWriter.write(folder, lines);
            summary(out, written, Duration.ofNanos(System.nanoTime() - started));
            versionIndex(out, bank, writeIndex(out, bank), noGit, git);
            if (open) opener.accept(folder);
            return 0;
        } catch (IOException e) {
            return fail(out, "No pude terminar: " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    private static int storeThreads() {
        try {
            return Math.max(1, ToolWiring.storeCount());
        } catch (IOException e) {
            // A broken stores.txt is reported properly when the finder is built, a moment later.
            return 8;
        }
    }

    private static String progressLine(int done, int total, DeliveryLine line, boolean fromCache) {
        var sb = new StringBuilder(String.format("[%" + Integer.toString(total).length() + "d/%d] SKU %-10s %-15s", done, total, line.sku(), line.stateName()));
        if (line.result() == Result.OK) sb.append(' ').append(line.store()).append(' ').append(line.side()).append(" px");
        else if (line.detail() != null) sb.append(' ').append(line.detail());
        if (!line.warnings().isEmpty()) sb.append("  (con aviso)");
        if (fromCache) sb.append("  (ya estaba)");
        return sb.toString();
    }

    private static void summary(PrintStream out, DeliveryWriter.Written written, Duration took) {
        long ok = count(written, Result.OK), none = count(written, Result.SIN_IMAGEN), error = count(written, Result.ERROR),
                invalid = count(written, Result.DATOS_INVALIDOS);
        Map<RowProblem, Long> byProblem = new EnumMap<>(RowProblem.class);
        for (DeliveryLine l : written.lines()) {
            if (l.result() == Result.DATOS_INVALIDOS && l.problem() != null) byProblem.merge(l.problem(), 1L, Long::sum);
        }
        long warned = written.lines().stream().filter(l -> l.result() == Result.OK && !l.warnings().isEmpty()).count();
        out.println();
        out.println("Listo en " + took.toSeconds() + " s. " + written.lines().size() + " filas:");
        out.println("  " + ok + " con foto" + (warned > 0 ? " (" + warned + " con avisos para mirar en la galería)" : ""));
        out.println("  " + none + " sin imagen");
        if (invalid > 0) {
            // Ignored, not asked: one block of counts, never a line per row. Each one is explained in reporte.csv.
            out.println("  " + invalid + " ignoradas por tener datos que corregir (no se consultaron; cada una está explicada en reporte.csv):");
            byProblem.forEach((problem, n) -> out.println("      " + n + " " + problem.summary()));
        }
        if (error > 0) {
            out.println("  " + error + " que no se pudieron consultar: volvé a correr la misma planilla,");
            out.println("    lo que ya se bajó queda guardado y solo se piden esas.");
        }
        out.println();
        out.println("Carpeta: " + written.folder());
        out.println("  fotos\\ (" + written.photoCount() + " fotos con el SKU de nombre), fotos.zip, reporte.csv, galeria.html");
        out.println("Para cambiar una foto, reemplazá el archivo en fotos\\ y volvé a armar el zip.");
    }

    private static long count(DeliveryWriter.Written written, Result result) {
        return written.lines().stream().filter(l -> l.result() == result).count();
    }

    private static void usage(PrintStream out) {
        out.println("Uso: arrastrá la planilla (.xlsx o .csv, con columnas SKU y EAN) sobre 'Arrastrar la planilla aca.bat' (o un acceso directo a ese archivo),");
        out.println("o por consola:  fotos-tool <planilla> [--refresh] [--cache <carpeta>] [--salida <carpeta>] [--paralelo <1-" + MAX_PARALLEL + ">] [--no-abrir]");
        out.println();
        out.println("  --refresh     vuelve a consultar las tiendas por los EAN de esta planilla (no pierde la foto guardada si no encuentra otra)");
        out.println("  --cache       carpeta del banco de fotos (por defecto, banco-fotos dentro de la carpeta del programa)");
        out.println("  --banco       sin planilla: reconoce las fotos propias dejadas en el banco (<EAN>.jpg) y arma el índice");
        out.println("  --sin-git     no commitea ni sube el índice del banco (por defecto lo hace si la carpeta del banco es un repo git)");
        out.println("  --salida      carpeta donde se crea \"Fotos <fecha>\" (por defecto, la carpeta del programa)");
        out.println("  --paralelo    cuántos productos se consultan a la vez (por defecto " + DEFAULT_PARALLEL + "); más rápido, más tráfico a las tiendas");
        out.println("  --no-abrir    no abre la carpeta al terminar");
    }

    private static int fail(PrintStream out, String message) {
        out.println(message);
        return 1;
    }

    /**
     * The bank outlives any one list: the same product in the next client's sheet is not asked again. Where it lives:
     * {@code --cache}, else {@code FOTOS_CACHE}, else {@code banco-fotos} inside the program folder (the installer never
     * touches it), else, when not running from an install, the old per-user folder.
     */
    private static Path defaultBankDir(Path installDir) {
        String override = System.getenv("FOTOS_CACHE");
        if (override != null && !override.isBlank()) return Path.of(override);
        if (installDir != null) return installDir.resolve("banco-fotos");
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("api-fotos").resolve("cache");
    }

    /** {@code --banco}: recognise the photos dropped in the bank, rewrite the index and say what the bank holds. */
    private static int tendBank(PrintStream out, LocalCache bank, Clock clock, boolean noGit, BankGit.Runner git) {
        out.println("Banco de fotos: " + bank.root());
        takeDroppedPhotos(out, bank, clock);
        versionIndex(out, bank, writeIndex(out, bank), noGit, git);
        try {
            int ready = 0, manual = 0, withOriginal = 0, without = 0;
            for (var ean : bank.eans()) {
                var entry = bank.find(ean);
                if (entry.isEmpty()) continue;
                if (entry.get().status() != CacheEntry.Status.READY) {
                    without++;
                    continue;
                }
                ready++;
                if (entry.get().source() == CacheEntry.Source.MANUAL) manual++;
                if (entry.get().hasOriginal()) withOriginal++;
            }
            out.println(ready + " fotos (" + manual + " propias, " + withOriginal + " con el original sin recortar) y "
                    + without + " EAN sin foto.");
        } catch (IOException e) {
            out.println("No pude leer el banco: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    /** Photos a person dropped in the bank: recognised before anything is looked up, so a run already uses them. */
    private static void takeDroppedPhotos(PrintStream out, LocalCache bank, Clock clock) {
        try {
            BankFiles.ensure(bank.root());
            BankIngest.Report report = BankIngest.ingest(bank, clock);
            if (!report.ingested().isEmpty()) {
                out.println("Fotos propias reconocidas en el banco: " + report.ingested().size() + " ("
                        + report.ingested().stream().limit(10).map(e -> e.value()).collect(Collectors.joining(", "))
                        + (report.ingested().size() > 10 ? ", ..." : "") + ")");
            }
            report.skipped().stream().limit(10).forEach(s ->
                    out.println("Se dejó sin tocar '" + s.file() + "' en el banco: " + s.reason() + "."));
            if (report.skipped().size() > 10) out.println("... y " + (report.skipped().size() - 10) + " archivos más que no se pudieron reconocer.");
        } catch (IOException e) {
            out.println("No pude revisar las fotos propias del banco: " + e.getMessage());
        }
    }

    /** The index is for the person and for git: a failure to write it never costs the delivery that was just made. */
    private static int writeIndex(PrintStream out, LocalCache bank) {
        try {
            int lines = BankIndex.write(bank);
            out.println("Banco de fotos: " + lines + " EAN en " + bank.root().resolve(BankIndex.FILE_NAME));
            return lines;
        } catch (IOException e) {
            out.println("No pude escribir el índice del banco: " + e.getMessage());
            return -1;
        }
    }

    /**
     * When the bank folder is a git repository, a changed index is committed (alone) and pushed. It is a convenience: any
     * failure is one line here and never costs the run or the delivery.
     */
    private static void versionIndex(PrintStream out, LocalCache bank, int eans, boolean noGit, BankGit.Runner git) {
        if (noGit || eans < 0) return;
        var report = BankGit.publishIndex(bank.root(), eans, git);
        switch (report.outcome()) {
            case PUSHED -> out.println("Índice versionado en git y subido.");
            case COMMITTED_ONLY -> out.println("Índice versionado en git, pero no se pudo subir (" + report.detail()
                    + "): se vuelve a intentar la próxima vez.");
            case FAILED -> out.println("No pude versionar el índice en git: " + report.detail() + ".");
            case NOT_A_REPO, UNCHANGED -> { }
        }
    }

    /**
     * The folder this program is installed in, found from where its jar is: {@code <install>/lib/fotos-tool-x.jar} with a
     * {@code <install>/bin} next to {@code lib}. Null when it is not an install (a build, a test, an IDE).
     */
    static Path installDir() {
        try {
            Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path lib = jar.getParent();
            Path install = lib == null ? null : lib.getParent();
            if (jar.toString().endsWith(".jar") && install != null && Files.isDirectory(install.resolve("bin"))) return install;
        } catch (Exception e) {
            // Fall through: not an install.
        }
        return null;
    }

    private static void openFolder(Path folder) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(folder.toFile());
            }
        } catch (IOException | RuntimeException e) {
            // Not worth failing the run for: the summary already says where the folder is.
        }
    }
}
