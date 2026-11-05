package com.axum.fotos.app.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, no key. Says WHICH build is running and since when.
 *
 * <p>It is the cheap smoke test of a deploy and what the external monitor looks at, so it exposes
 * nothing about any client.
 */
@RestController
public class HealthController {

    private final String version;
    private final String commit;
    private final Clock clock;

    /**
     * When THIS process started: it answers "did it restart last night?" without anyone logging into
     * the server, which was the first question of every incident in the sibling services.
     *
     * <p>In the configured zone, NOT the server clock's. The store server runs in UTC-12, nine hours
     * behind Argentina: a time read in a hurry after an incident is subtracted wrong and lands nine
     * hours off.
     */
    private final OffsetDateTime startedAt;

    HealthController(@Value("${fotos.version:dev}") String version,
                     @Value("${fotos.commit:unknown}") String commit,
                     Clock clock) {
        this.version = version;
        this.commit = commit;
        this.clock = clock;
        this.startedAt = OffsetDateTime.now(clock);
    }

    /**
     * @param commit the commit the jar was built from. <b>It is the datum the version cannot give</b>:
     *               a person types the version and two builds can carry the same one. If it ends in
     *               {@code +cambios} the jar came from a tree with uncommitted changes and matches
     *               no commit
     */
    @GetMapping("/health")
    public Health health() {
        return new Health("ok", version, commit,
                // ISO 8601 with zone, a single date format across the whole API.
                startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                upFor(startedAt.toInstant(), Instant.now(clock)));
    }

    /** How long it has been up, in words: the instant alone forces the reader to do the math. */
    static String upFor(Instant since, Instant now) {
        var d = Duration.between(since, now);
        if (d.toMinutes() < 1) return d.toSeconds() + " s";
        if (d.toHours() < 1) return d.toMinutes() + " min";
        if (d.toDays() < 1) return d.toHours() + " h " + (d.toMinutes() % 60) + " min";
        return d.toDays() + " d " + (d.toHours() % 24) + " h";
    }

    public record Health(String status, String version, String commit, String startedAt, String upFor) {}
}
