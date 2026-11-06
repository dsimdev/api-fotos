package com.axum.fotos.tool;

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

/**
 * The way a person uses this: drop a product list (xlsx or csv with SKU and EAN) on the launcher and get a folder
 * with one photo per SKU. Everything it prints is for that person, so it is in Spanish.
 *
 * <pre>
 *   fotos-tool &lt;planilla&gt; [--refresh] [--cache &lt;carpeta&gt;] [--salida &lt;carpeta&gt;] [--paralelo &lt;1-8&gt;] [--no-abrir]
 * </pre>
 *
 * <p>Each run writes a new folder {@code Fotos <date>} INSIDE the folder the program is installed in (the sheet is
 * usually dropped from Downloads, and that is not where the deliveries should pile up). {@code --salida} puts them
 * elsewhere. Run from a build and not from an install, with no {@code --salida}, it writes next to the sheet.
 *
 * <p>The cache lives outside the delivery folder so that the next list reuses every photo already found.
 * {@code --refresh} forgets what is known about the EANs of THIS list and asks the stores again.
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
        Path sheet = null;
        Path cacheDir = null;
        Path outputRoot = null;
        int parallel = DEFAULT_PARALLEL;
        boolean refresh = false, open = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--refresh" -> refresh = true;
                case "--no-abrir" -> open = false;
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

        if (cacheDir == null) cacheDir = defaultCacheDir();
        if (outputRoot == null) outputRoot = installDir != null ? installDir : sheet.toAbsolutePath().getParent();
        // Each product asks every store at once, so the threads scale with the number of stores AND with how many
        // products run together (a fixed number would silently queue the lookups once there are more stores).
        ExecutorService pool = Executors.newFixedThreadPool(storeThreads() * parallel);
        try {
            long started = System.nanoTime();
            long ignoredRows = RowChecker.check(rows).stream().filter(c -> !c.usable()).count();
            out.println("Planilla: " + sheet.getFileName() + " (" + rows.size() + " filas: " + (rows.size() - ignoredRows)
                    + " para consultar" + (ignoredRows > 0 ? " y " + ignoredRows + " que se ignoran por tener datos que corregir" : "") + ")");
            out.println("Caché de fotos: " + cacheDir);
            out.println("Las entregas se guardan en: " + outputRoot);
            out.println("Productos que se consultan a la vez: " + parallel);
            out.println(refresh ? "Se vuelve a consultar todo (--refresh)." : "Lo que ya se bajó antes sale de la caché.");
            out.println();

            PhotoFinder finder = factory.create(cacheDir, pool);
            List<DeliveryLine> lines = BatchRun.run(rows, finder, refresh, parallel, (done, total, line, fromCache) ->
                    out.println(progressLine(done, total, line, fromCache)));

            Path folder = DeliveryWriter.newFolder(outputRoot, "Fotos " + LocalDate.now(clock));
            var written = DeliveryWriter.write(folder, lines);
            summary(out, written, Duration.ofNanos(System.nanoTime() - started));
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
        out.println("  --refresh     vuelve a consultar las tiendas por los EAN de esta planilla");
        out.println("  --cache       carpeta donde se guardan las fotos ya encontradas");
        out.println("  --salida      carpeta donde se crea \"Fotos <fecha>\" (por defecto, la carpeta del programa)");
        out.println("  --paralelo    cuántos productos se consultan a la vez (por defecto " + DEFAULT_PARALLEL + "); más rápido, más tráfico a las tiendas");
        out.println("  --no-abrir    no abre la carpeta al terminar");
    }

    private static int fail(PrintStream out, String message) {
        out.println(message);
        return 1;
    }

    /** The cache outlives any one list: the same product in the next client's sheet is not asked again. */
    private static Path defaultCacheDir() {
        String override = System.getenv("FOTOS_CACHE");
        if (override != null && !override.isBlank()) return Path.of(override);
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("api-fotos").resolve("cache");
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
