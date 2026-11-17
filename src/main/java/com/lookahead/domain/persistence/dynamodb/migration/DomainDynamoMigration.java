package com.lookahead.domain.persistence.dynamodb.migration;

import com.lookahead.domain.Events;
import com.lookahead.domain.persistence.dynamodb.DynamoSettings;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * DLV-970: the migration entrypoint the Infra migration task runs from the application image, before the app
 * of the same build is deployed ({@code data first, then app}; no pipeline gate):
 * <pre>
 * java -cp /opt/lookahead/app.jar -Dloader.main=com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoMigration \
 *      org.springframework.boot.loader.launch.PropertiesLauncher migrate|verify|status
 * </pre>
 * Inputs are process variables: LOOKAHEAD_ENVIRONMENT, AWS_REGION, the four LOOKAHEAD_DYNAMODB_*_TABLE names
 * (dev/prod) or LOOKAHEAD_DYNAMODB_ENDPOINT (local) and GITHUB_RUN_ID for the run identity. Exit 0 means the command
 * succeeded, 1 that it failed and the ledger is unchanged past the last recorded step, 2 a usage error.
 * The migration-task role alone may write the ledger; the running app's role only reads it.
 */
public final class DomainDynamoMigration {
    private DomainDynamoMigration() { }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, Clock.systemUTC()));
    }

    static int run(String[] args, Map<String, String> variables, PrintStream out, Clock clock) {
        if (args.length != 1 || !(args[0].equals("migrate") || args[0].equals("verify") || args[0].equals("status"))) {
            out.println("usage: DomainDynamoMigration migrate|verify|status");
            return 2;
        }
        String command = args[0];
        try {
            var settings = DynamoSettings.fromVariables(variables);
            String runId = variables.getOrDefault("GITHUB_RUN_ID", "").isBlank() ? "local-" + UUID.randomUUID() : "github-" + variables.get("GITHUB_RUN_ID").trim();
            try (var client = settings.client()) {
                var runner = new DynamoMigrationRunner(client, settings.tables(), DomainDynamoCatalog.catalog(), clock);
                DynamoMigrationRunner.Outcome outcome = switch (command) {
                    case "migrate" -> runner.migrate(runId);
                    case "verify" -> runner.verify();
                    default -> runner.status();
                };
                out.println("{\"command\":\"" + command + "\",\"environment\":\"" + settings.environment() + "\",\"runId\":\"" + runId
                        + "\",\"before\":" + outcome.before() + ",\"after\":" + outcome.after() + ",\"required\":" + DomainDynamoCatalog.REQUIRED
                        + ",\"applied\":" + json(outcome.applied()) + ",\"verified\":" + json(outcome.verified()) + ",\"ahead\":" + outcome.ahead() + "}");
                return 0;
            }
        } catch (RuntimeException failure) {
            // Another run holding the lock is an expected refusal (WARN, our own words). Everything else is an ERROR an
            // operator acts on (plain logs, owner 2026-11-17): the dependency's own message, the hint, and what happens next.
            // The JSON report line below is parsed by tools/release/migrate.py and stays exactly as it is.
            if (failure instanceof DynamoMigrationLock.Locked) {
                Events.failed(DynamoMigrationRunner.LOG, "migration." + command, "LOCKED").addKeyValue("error.type", Events.types(failure))
                        .log("Migration {} failed: {}", command, failure.getMessage());
            } else {
                String reason = failure instanceof DynamoMigrationLedger.Conflict ? "LEDGER_CONFLICT" : "FAILED";
                DynamoMigrationRunner.LOG.atError().addKeyValue("event.action", "migration." + command).addKeyValue("event.outcome", "failure")
                        .addKeyValue("reason", reason).addKeyValue("error.type", Events.types(failure))
                        .log("Migration {} failed: {}{}; the task exits 1 and the ledger is unchanged past the last recorded step", command,
                                failure instanceof DynamoMigrationLedger.Conflict || failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                                        ? Events.scrub(failure.getMessage()) + " (" + reason + ")" : Events.quoted(failure), DynamoMigrationRunner.hint(failure));
            }
            out.println("{\"command\":\"" + command + "\",\"failed\":true,\"error\":\"" + Events.types(failure).replace("\"", "") + "\"}");
            return 1;
        }
    }

    private static String json(java.util.List<String> values) {
        var text = new StringBuilder("[");
        for (String value : values) text.append(text.length() == 1 ? "" : ",").append('"').append(value.replace("\"", "")).append('"');
        return text.append(']').toString();
    }
}
