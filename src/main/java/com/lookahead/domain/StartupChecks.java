package com.lookahead.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DLV-969 (owner, 2026-11-04): a startup check that refuses to start writes one WARN line on {@code lookahead.startup}
 * first, naming the setting, secret or file by its NAME (environment variable or property) and what is wrong with it,
 * never its value, and the exception types when there are any. The exception that stops startup keeps its fixed text
 * (Spring then logs "Application run failed").
 */
public final class StartupChecks {
    public static final Logger LOG = LoggerFactory.getLogger("lookahead.startup");

    private StartupChecks() { }

    /** WARN: e.g. "Startup refused: LOOKAHEAD_TLS_SERVER_KEY does not match LOOKAHEAD_TLS_SERVER_CERTIFICATE". */
    public static void refused(String setting, String reason, String problem) { refused(setting, reason, problem, null); }

    /** As above, with the types of the failure that showed the problem (never its message). */
    public static void refused(String setting, String reason, String problem, Throwable failure) {
        var line = Events.refused(LOG, "startup.check", reason).addKeyValue("setting", setting);
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
