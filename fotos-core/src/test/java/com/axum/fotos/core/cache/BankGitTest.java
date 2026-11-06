package com.axum.fotos.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.cache.BankGit.Outcome;
import com.axum.fotos.core.cache.BankGit.Result;
import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankGitTest {

    @TempDir
    Path dir;

    /** A git that records what it was asked and answers from a script. */
    private static final class FakeGit implements BankGit.Runner {
        final List<List<String>> calls = new ArrayList<>();
        final Function<List<String>, Result> script;

        FakeGit(Function<List<String>, Result> script) {
            this.script = script;
        }

        @Override
        public Result run(Path dir, List<String> command, Duration timeout) {
            calls.add(command);
            return script.apply(command);
        }

        List<String> verbs() {
            return calls.stream().map(c -> c.get(1)).toList();
        }
    }

    private static Result ok(String output) {
        return new Result(0, output);
    }

    private Path repoFolder() throws IOException {
        return Files.createDirectories(dir.resolve("banco").resolve(".git")).getParent();
    }

    @Test
    void aFolderThatIsNotARepositoryIsLeftAloneAndGitIsNotEvenRun() throws IOException {
        var git = new FakeGit(c -> ok(""));

        var report = BankGit.publishIndex(Files.createDirectories(dir.resolve("banco")), 5, git);

        assertEquals(Outcome.NOT_A_REPO, report.outcome());
        assertTrue(git.calls.isEmpty());
    }

    @Test
    void aChangedIndexIsCommittedAloneAndPushed() throws IOException {
        var git = new FakeGit(c -> c.get(1).equals("status") ? ok(" M indice.csv\n") : ok(""));

        var report = BankGit.publishIndex(repoFolder(), 196, git);

        assertEquals(Outcome.PUSHED, report.outcome());
        assertEquals(List.of("status", "add", "commit", "push"), git.verbs());
        assertEquals(List.of("git", "add", "--", "indice.csv"), git.calls.get(1));
        assertEquals(List.of("git", "commit", "-m", "chore(index): update the index (196 EAN)", "--", "indice.csv"), git.calls.get(2),
                "only the index goes in the commit, and the message is made of a number");
    }

    @Test
    void aNewIndexThatGitHasNeverSeenIsCommittedToo() throws IOException {
        var git = new FakeGit(c -> c.get(1).equals("status") ? ok("?? indice.csv\n") : ok(""));

        assertEquals(Outcome.PUSHED, BankGit.publishIndex(repoFolder(), 3, git).outcome());
    }

    @Test
    void anUnchangedIndexWithNothingToSendDoesNothing() throws IOException {
        var git = new FakeGit(c -> c.get(1).equals("rev-list") ? ok("0\n") : ok(""));

        var report = BankGit.publishIndex(repoFolder(), 196, git);

        assertEquals(Outcome.UNCHANGED, report.outcome());
        assertEquals(List.of("status", "rev-list"), git.verbs(), "no commit, no push");
    }

    /** A push that failed last time leaves a commit only on this machine: this run sends it, without a new commit. */
    @Test
    void anUnchangedIndexButACommitThatNeverLeftIsPushedNow() throws IOException {
        var git = new FakeGit(c -> c.get(1).equals("rev-list") ? ok("1\n") : ok(""));

        var report = BankGit.publishIndex(repoFolder(), 196, git);

        assertEquals(Outcome.PUSHED, report.outcome());
        assertEquals(List.of("status", "rev-list", "push"), git.verbs());
    }

    @Test
    void withoutAnUpstreamThereIsNothingToSend() throws IOException {
        var git = new FakeGit(c -> c.get(1).equals("rev-list") ? new Result(128, "fatal: no upstream configured") : ok(""));

        assertEquals(Outcome.UNCHANGED, BankGit.publishIndex(repoFolder(), 196, git).outcome());
    }

    @Test
    void aFailedPushStillCountsAsCommittedAndSaysWhy() throws IOException {
        var git = new FakeGit(c -> switch (c.get(1)) {
            case "status" -> ok(" M indice.csv\n");
            case "push" -> new Result(1, "\nfatal: Could not resolve host: github.com\nhint: more text\n");
            default -> ok("");
        });

        var report = BankGit.publishIndex(repoFolder(), 196, git);

        assertEquals(Outcome.COMMITTED_ONLY, report.outcome());
        assertEquals("fatal: Could not resolve host: github.com", report.detail(), "one line, the first with something in it");
    }

    @Test
    void aFailedCommitIsReportedAndNothingIsPushed() throws IOException {
        var git = new FakeGit(c -> switch (c.get(1)) {
            case "status" -> ok(" M indice.csv\n");
            case "commit" -> new Result(128, "Author identity unknown\n*** Please tell me who you are.");
            default -> ok("");
        });

        var report = BankGit.publishIndex(repoFolder(), 196, git);

        assertEquals(Outcome.FAILED, report.outcome());
        assertEquals("Author identity unknown", report.detail());
        assertFalse(git.verbs().contains("push"));
    }

    @Test
    void aBrokenRepositoryIsReportedFromTheFirstCommand() throws IOException {
        var git = new FakeGit(c -> new Result(128, "fatal: not a git repository"));

        var report = BankGit.publishIndex(repoFolder(), 196, git);

        assertEquals(Outcome.FAILED, report.outcome());
        assertEquals(List.of("status"), git.verbs());
    }

    @Test
    void gitNotBeingInstalledIsAReportNotACrash() throws IOException {
        BankGit.Runner noGit = (d, c, t) -> {
            throw new IOException("no pude ejecutar git (¿está instalado?)");
        };

        var report = BankGit.publishIndex(repoFolder(), 196, noGit);

        assertEquals(Outcome.FAILED, report.outcome());
        assertTrue(report.detail().contains("git"));
    }

    // ---- with the real git, against a bare repository on disk acting as the remote ----

    private static boolean gitIsAvailable() {
        try {
            return BankGit.SYSTEM.run(Path.of("."), List.of("git", "--version"), Duration.ofSeconds(20)).exit() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void git(Path where, String... args) throws IOException {
        var command = new ArrayList<String>(List.of("git"));
        command.addAll(List.of(args));
        Result r = BankGit.SYSTEM.run(where, command, Duration.ofSeconds(60));
        if (r.exit() != 0) throw new IOException("git " + String.join(" ", args) + " -> " + r.output());
    }

    private static String gitOut(Path where, String... args) throws IOException {
        var command = new ArrayList<String>(List.of("git"));
        command.addAll(List.of(args));
        return BankGit.SYSTEM.run(where, command, Duration.ofSeconds(60)).output();
    }

    private LocalCache realBankWithARemote() throws IOException {
        Path remote = Files.createDirectories(dir.resolve("remote.git"));
        git(remote, "init", "--bare", "-b", "main");
        Path bank = Files.createDirectories(dir.resolve("banco-real"));
        git(bank, "init", "-b", "main");
        git(bank, "config", "user.name", "Prueba");
        git(bank, "config", "user.email", "prueba@example.com");
        git(bank, "config", "commit.gpgsign", "false");
        BankFiles.ensure(bank);
        git(bank, "remote", "add", "origin", remote.toAbsolutePath().toString());
        git(bank, "add", "-A");
        git(bank, "commit", "-m", "chore: start");
        git(bank, "push", "-u", "origin", "main");
        return new LocalCache(bank);
    }

    @Test
    void withRealGitTheIndexReachesTheRemoteAndNothingElseDoes() throws IOException {
        assumeTrue(gitIsAvailable(), "git is not installed on this machine");
        LocalCache bank = realBankWithARemote();
        Path remote = dir.resolve("remote.git");
        // A photo in the bank and an unrelated edit in the folder: neither may travel with the index.
        bank.saveReady(Ean.parse("7791337010604"), new Candidate("jumbo", "https://page", "https://img", Candidate.Kind.ORIGINAL,
                ImageFormat.JPEG, 900, 0.0, 200.0, new byte[]{1, 2, 3}), Instant.parse("2026-11-06T15:00:00Z"));
        Files.writeString(bank.root().resolve("README.md"), "editado a mano", StandardCharsets.UTF_8);
        int eans = BankIndex.write(bank);

        var report = BankGit.publishIndex(bank.root(), eans, BankGit.SYSTEM);

        assertEquals(Outcome.PUSHED, report.outcome(), report.detail());
        String filesInTheCommit = gitOut(remote, "show", "--name-only", "--format=", "main").trim();
        assertEquals("indice.csv", filesInTheCommit, "only the index was committed");
        assertTrue(gitOut(remote, "show", "main:indice.csv").contains("7791337010604"));
        assertTrue(gitOut(remote, "ls-tree", "-r", "--name-only", "main").lines().noneMatch(n -> n.contains("image.jpg") || n.contains("entry.properties")),
                "no photo ever reaches the remote");
        assertTrue(gitOut(bank.root(), "status", "--porcelain").contains("README.md"), "the hand edit is still there, uncommitted");
    }

    @Test
    void withRealGitAnUnchangedIndexMakesNoCommitAtAll() throws IOException {
        assumeTrue(gitIsAvailable(), "git is not installed on this machine");
        LocalCache bank = realBankWithARemote();
        bank.saveEmpty(Ean.parse("7791337010604"), CacheEntry.Status.NOT_FOUND, "Ninguna tienda lo vende.", Instant.parse("2026-11-06T15:00:00Z"));
        BankGit.publishIndex(bank.root(), BankIndex.write(bank), BankGit.SYSTEM);
        String before = gitOut(bank.root(), "rev-list", "--count", "HEAD").trim();

        BankIndex.write(bank);
        var second = BankGit.publishIndex(bank.root(), 1, BankGit.SYSTEM);

        assertEquals(Outcome.UNCHANGED, second.outcome());
        assertEquals(before, gitOut(bank.root(), "rev-list", "--count", "HEAD").trim());
    }

    @Test
    void withRealGitAMissingRemoteLeavesTheCommitAndTheNextRunSendsIt() throws IOException {
        assumeTrue(gitIsAvailable(), "git is not installed on this machine");
        LocalCache bank = realBankWithARemote();
        Path remote = dir.resolve("remote.git");
        Path moved = dir.resolve("remote-away.git");
        Files.move(remote, moved);
        bank.saveEmpty(Ean.parse("7791337010604"), CacheEntry.Status.NOT_FOUND, "x", Instant.parse("2026-11-06T15:00:00Z"));

        var offline = BankGit.publishIndex(bank.root(), BankIndex.write(bank), BankGit.SYSTEM);

        assertEquals(Outcome.COMMITTED_ONLY, offline.outcome());
        Files.move(moved, remote);
        var back = BankGit.publishIndex(bank.root(), BankIndex.write(bank), BankGit.SYSTEM);
        assertEquals(Outcome.PUSHED, back.outcome(), back.detail());
        assertTrue(gitOut(remote, "show", "main:indice.csv").contains("7791337010604"));
    }

    @Test
    void theFirstLineOfGitsAnswerIsWhatIsShown() {
        assertEquals("uno", BankGit.firstLine("\n  \nuno\ndos\n"));
        assertEquals("sin detalle", BankGit.firstLine("   \n"));
    }
}
