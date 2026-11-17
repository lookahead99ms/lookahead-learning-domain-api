package com.lookahead.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DLV-969 (owner, 2026-11-04): a startup check that refuses to start writes one line on {@code lookahead.startup}
 * first, naming the setting, secret or file by its NAME (environment variable or property) and what is wrong with it,
 * never its value, and the exception types when there are any. The exception that stops startup keeps its fixed text
 * (Spring then logs "Application run failed" with the stack trace). Plain logs (owner, 2026-11-17): a refused startup
 * is a misconfiguration an operator must fix, so the line is an ERROR; the sentence already names the setting and the
 * problem, which is the hint. The failure's message is NOT quoted here, on purpose: these checks validate the values
 * of settings (endpoint URLs, secrets, certificate files), and a validation exception may echo the value it rejected.
 */
public final class StartupChecks {
    public static final Logger LOG = LoggerFactory.getLogger("lookahead.startup");

    private StartupChecks() { }

    /** ERROR: e.g. "Startup refused: LOOKAHEAD_TLS_SERVER_KEY does not match LOOKAHEAD_TLS_SERVER_CERTIFICATE". */
    public static void refused(String setting, String reason, String problem) { refused(setting, reason, problem, null); }

    /** As above, with the types of the failure that showed the problem (never its message). */
    public static void refused(String setting, String reason, String problem, Throwable failure) {
        var line = LOG.atError().addKeyValue("event.action", "startup.check").addKeyValue("event.outcome", "refused").addKeyValue("reason", reason)
                .addKeyValue("setting", setting);
        if (failure == null) {
            line.log("Startup refused: {} {}; the app stops before it takes traffic", setting, problem);
            return;
        }
        String types = Events.types(failure);
        line.addKeyValue("error.type", types).log("Startup refused: {} {} ({}); the app stops before it takes traffic", setting, problem, types);
    }

    /** Logs as {@link #refused(String, String, String)} and returns the exception to throw, with its fixed text. */
    public static IllegalStateException refuse(String setting, String reason, String problem, String message) {
        refused(setting, reason, problem);
        return new IllegalStateException(message);
    }
}
