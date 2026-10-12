package com.lookahead.domain.cloud;

import com.lookahead.learning.content.exception.AccountFailure;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Public profile links: https only, no credentials or odd ports, and known hosts for the named networks. */
public final class ProfileLinkPolicy {
    public static final String CODE = "INVALID_PROFILE_LINK";
    public static final List<String> KINDS = List.of("github", "linkedin", "x", "website");
    static final int MAX_CODE_POINTS = 300;
    private static final Map<String, Set<String>> HOSTS = Map.of(
            "github", Set.of("github.com", "www.github.com"),
            "linkedin", Set.of("linkedin.com", "www.linkedin.com"),
            "x", Set.of("x.com", "www.x.com", "twitter.com", "www.twitter.com"));
    private static final Map<String, String> MESSAGES = Map.of(
            "github", "Use an https://github.com/… address for GitHub.",
            "linkedin", "Use an https://linkedin.com/… address for LinkedIn.",
            "x", "Use an https://x.com/… address for X.",
            "website", "Use an https:// address for your website.");

    private ProfileLinkPolicy() { }

    /** Returns the stripped value to store, or {@code null} when the link is cleared. */
    public static String validate(String kind, String value) {
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unknown profile link kind");
        if (value == null) return null;
        String link = value.strip();
        if (link.isEmpty()) return null;
        if (!acceptable(kind, link)) throw new AccountFailure(422, CODE, MESSAGES.get(kind));
        return link;
    }

    public static AccountFailure invalidShape() {
        return new AccountFailure(422, CODE, "Provide the github, linkedin, x and website fields.");
    }

    private static boolean acceptable(String kind, String link) {
        if (link.codePointCount(0, link.length()) > MAX_CODE_POINTS) return false;
        if (link.codePoints().anyMatch(code -> Character.isISOControl(code) || Character.isWhitespace(code)
                || Character.isSpaceChar(code) || code >= 0xD800 && code <= 0xDFFF)) return false;
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException invalid) {
            return false;
        }
        if (!uri.isAbsolute() || uri.isOpaque() || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) return false;
        if (uri.getRawUserInfo() != null || uri.getHost() == null || uri.getHost().isEmpty()) return false;
        if (uri.getPort() != -1 && uri.getPort() != 443) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Set<String> allowed = HOSTS.get(kind);
        if (allowed != null) return allowed.contains(host) && hasPathSegment(uri.getRawPath());
        return publicHostName(host);
    }

    private static boolean hasPathSegment(String path) {
        if (path == null) return false;
        for (String segment : path.split("/")) if (!segment.isEmpty()) return true;
        return false;
    }

    private static boolean publicHostName(String host) {
        if (host.startsWith("[") || !host.contains(".")) return false;
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (name.equals("localhost") || name.endsWith(".localhost")) return false;
        // Browsers read a numeric (or 0x-prefixed) final label as an IPv4 address.
        String last = name.substring(name.lastIndexOf('.') + 1);
        return !last.isEmpty() && !last.matches("[0-9]+|0x[0-9a-f]*");
    }
}
