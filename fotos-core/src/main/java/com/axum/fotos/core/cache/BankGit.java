package com.axum.fotos.core.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Versions the bank's index: when the bank folder is a git repository, a changed {@code indice.csv} is committed
 * (and only that file) and pushed. The photos never go to git: the bank's {@code .gitignore} keeps them out.
 *
 * <p>It is a convenience and must never cost a run: every failure (no git, no remote, no network, a rejected push) comes
 * back as a {@link Report} to be shown in one line. It never forces anything and never pulls: if the remote moved on its
 * own, the push is rejected and a person decides.
 *
 * <p>No shell is involved: the commands are fixed lists of arguments, and the only text built from data is the commit
 * message, made of a number. Git is told not to ask anything ({@code GIT_TERMINAL_PROMPT=0}), so a missing credential
 * fails instead of hanging the run, and every call has a time limit.
 */
public final class BankGit {

    public record Result(int exit, String output) {}

    /** Runs one command in a folder; behind an interface so the logic is tested without git and without a network. */
    @FunctionalInterface
    public interface Runner {
        Result run(Path dir, List<String> command, Duration timeout) throws IOException;
    }

    public enum Outcome {
        /** The bank folder is not a git repository: nothing to do, and nothing to say. */
        NOT_A_REPO,
        /** The index did not change and everything committed was already pushed. */
        UNCHANGED,
        /** Committed and pushed. */
        PUSHED,
        /** Committed here, but the push failed: the next run tries again. */
        COMMITTED_ONLY,
        /** Could not commit (no git, a broken repo, no user configured...). */
        FAILED
    }

    public record Report(Outcome outcome, String detail) {}

    private static final String INDEX = BankIndex.FILE_NAME;
    private static final Duration LOCAL = Duration.ofSeconds(30);
    private static final Duration NETWORK = Duration.ofSeconds(90);

    /** The real git of this machine. */
    public static final Runner SYSTEM = BankGit::runSystemGit;

    private BankGit() {}

    /** @param eans how many EANs the index lists, for the commit message */
    public static Report publishIndex(Path bankRoot, int eans, Runner git) {
        if (!Files.isDirectory(bankRoot.resolve(".git"))) return new Report(Outcome.NOT_A_REPO, "");
        try {
            Result status = git.run(bankRoot, List.of("git", "status", "--porcelain", "--", INDEX), LOCAL);
            if (status.exit() != 0) return failed(status);

            boolean changed = !status.output().isBlank();
            if (changed) {
                Result add = git.run(bankRoot, List.of("git", "add", "--", INDEX), LOCAL);
                if (add.exit() != 0) return failed(add);
                // Only the index goes in this commit, whatever else is modified in the folder.
                Result commit = git.run(bankRoot, List.of("git", "commit", "-m", "chore(index): update the index (" + eans + " EAN)", "--", INDEX), LOCAL);
                if (commit.exit() != 0) return failed(commit);
            } else if (!hasUnpushedCommits(git, bankRoot)) {
                return new Report(Outcome.UNCHANGED, "");
            }

            Result push = git.run(bankRoot, List.of("git", "push"), NETWORK);
            if (push.exit() != 0) return new Report(Outcome.COMMITTED_ONLY, firstLine(push.output()));
            return new Report(Outcome.PUSHED, "");
        } catch (IOException e) {
            return new Report(Outcome.FAILED, e.getMessage());
        }
    }

    /** A push that failed last time leaves a commit that is only here: this run sends it. No upstream counts as "nothing to send". */
    private static boolean hasUnpushedCommits(Runner git, Path root) throws IOException {
        Result ahead = git.run(root, List.of("git", "rev-list", "--count", "@{upstream}..HEAD"), LOCAL);
        if (ahead.exit() != 0) return false;
        try {
            return Integer.parseInt(ahead.output().trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Report failed(Result result) {
        return new Report(Outcome.FAILED, firstLine(result.output()));
    }

    /** One line is what a person reads; git can be chatty. */
    static String firstLine(String output) {
        return output.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse("sin detalle");
    }

    private static Result runSystemGit(Path dir, List<String> command, Duration timeout) throws IOException {
        var builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("no pude ejecutar git (¿está instalado?)", e);
        }
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("git no respondió a tiempo");
            }
            return new Result(process.exitValue(), output.join());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("se interrumpió mientras corría git", e);
        }
    }
}
