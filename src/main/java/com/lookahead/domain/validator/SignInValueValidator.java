package com.lookahead.domain.validator;

import static com.lookahead.domain.util.SignInRules.failure;
import static com.lookahead.domain.util.SignInRules.invalid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The values of a sign-in request, once its shape is known ({@link SignInRequestValidator} checks the shape): the label
 * of a sign-in, the display name, the theme, the four profile links and the form of a challenge token.
 * DLV-1016: these checks were written inside SignInService; they are here word for word, and the service calls each one
 * at the place the check always ran (the display name and the token on purpose after the sign-in itself was checked).
 */
public final class SignInValueValidator {
    private SignInValueValidator() {}

    /** A sign-in label: at most 80 characters once stripped, no control characters, no angle brackets. */
    public static void label(String label) {
        if (label == null || label.strip().length() > 80 || label.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>')) throw failure(400, "INVALID_SIGN_IN_LABEL");
    }

    /** The display name to store: stripped, 1 to 160 code points, no control characters and no surrogates. */
    public static String displayName(String displayName) {
        String name = displayName.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 160 || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) throw failure(422, "INVALID_PROFILE");
        return name;
    }

    public static void theme(String theme) {
        if (theme == null || !Set.of("light", "dark", "system").contains(theme)) throw failure(422, "INVALID_PREFERENCES");
    }

    /** The four profile links to store, in their fixed order: exactly the four kinds, each checked by {@link AccountProfileLinkValidator}. */
    public static Map<String, String> links(Map<String, String> links) {
        if (links == null || !links.keySet().equals(Set.copyOf(AccountProfileLinkValidator.KINDS))) throw AccountProfileLinkValidator.invalidShape();
        var values = new LinkedHashMap<String, String>();
        for (String kind : AccountProfileLinkValidator.KINDS) values.put(kind, AccountProfileLinkValidator.validate(kind, links.get(kind)));
        return values;
    }

    /** The form of a challenge token: 43 URL-safe characters. */
    public static void challengeToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
    }
}
