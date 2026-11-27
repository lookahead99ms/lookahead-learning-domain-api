package com.lookahead.domain.validator;

import com.lookahead.domain.dto.SignInPasswordChange;
import com.lookahead.domain.exception.AccountFailure;
import java.util.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** DLV-1016: the request-shape checks of the internal sign-in routes, moved here from SignInController's one handler. */
class SignInRequestValidatorTest {
    static final String BASE = "/internal/v1/cloud-sign-ins/";
    static final String ID = "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d";
    final SignInRequestValidator shapes = new SignInRequestValidator();

    @Test void anAddressIsPlainOnlyWhenItEndsWithTheRouteNameAsWritten() {
        assertThat(shapes.plain(BASE + "profile", "profile")).isTrue();
        assertThat(shapes.plain(BASE + "profil%65", "profile")).isFalse();
        assertThat(shapes.plain(BASE + "revoke-others", "revoke")).isFalse();
        assertThat(shapes.plain(BASE + "profile;x=1", "profile")).isFalse();
    }
    @Test void aRouteWithoutFieldsTakesAnEmptyObjectOnly() {
        for (String route : List.of("admit", "cancel", "revoke-others", "logout")) {
            assertThatCode(() -> shapes.empty(BASE + route, route, Map.of())).doesNotThrowAnyException();
            for (Object body : bodies(Map.of("unknown", true))) invalid(() -> shapes.empty(BASE + route, route, body));
        }
        invalid(() -> shapes.empty(BASE + "adm%69t", "admit", Map.of()));
    }
    @Test void aDisplayNameOrALabelIsTheOneStringField() {
        assertThat(shapes.displayName(BASE + "profile", Map.of("displayName", "Ada"))).isEqualTo("Ada");
        assertThat(shapes.displayName(BASE + "profile", Map.of("displayName", ""))).isEmpty();
        assertThat(shapes.label(BASE + "label", Map.of("label", "Laptop"))).isEqualTo("Laptop");
        for (Object body : bodies(Map.of(), Map.of("displayName", 7), Map.of("displayName", "Ada", "x", 1), nullValue("displayName"))) invalid(() -> shapes.displayName(BASE + "profile", body));
        for (Object body : bodies(Map.of(), Map.of("label", 7), Map.of("label", "Laptop", "x", 1), nullValue("label"))) invalid(() -> shapes.label(BASE + "label", body));
        invalid(() -> shapes.displayName(BASE + "profil%65", Map.of("displayName", "Ada")));
        invalid(() -> shapes.label(BASE + "lab%65l", Map.of("label", "Laptop")));
    }
    @Test void aThemeKeepsItsOwnRefusalForEveryWrongBody() {
        assertThat(shapes.theme(BASE + "preferences", Map.of("theme", "dark"))).isEqualTo("dark");
        for (Object body : bodies(Map.of(), Map.of("theme", 7), Map.of("theme", "dark", "x", 1), Map.of("unknown", true), nullValue("theme")))
            refused(() -> shapes.theme(BASE + "preferences", body), 422, "INVALID_PREFERENCES", "Choose light, dark or system.");
        // An address that is not written plainly is refused as any other sign-in request, before the body is looked at.
        invalid(() -> shapes.theme(BASE + "preference%73", null));
        invalid(() -> shapes.theme(BASE + "preference%73", Map.of("theme", "dark")));
    }
    @Test void linksAreTheFourKindsInTheirFixedOrderEachAStringOrNull() {
        var sent = new HashMap<String, Object>();
        sent.put("website", "https://example.org"); sent.put("x", null); sent.put("github", "https://github.com/a"); sent.put("linkedin", " ");
        var links = shapes.links(BASE + "links", sent);
        assertThat(links.keySet()).containsExactly("github", "linkedin", "x", "website");
        assertThat(links).containsEntry("github", "https://github.com/a").containsEntry("linkedin", " ").containsEntry("x", null).containsEntry("website", "https://example.org");
        var number = new HashMap<>(sent); number.put("x", 7);
        var extra = new HashMap<>(sent); extra.put("extra", "e");
        var missing = new HashMap<>(sent); missing.remove("website");
        for (Object body : bodies(Map.of(), number, extra, missing))
            refused(() -> shapes.links(BASE + "links", body), 422, "INVALID_PROFILE_LINK", "Provide the github, linkedin, x and website fields.");
        invalid(() -> shapes.links(BASE + "link%73", null));
        invalid(() -> shapes.links(BASE + "link%73", sent));
    }
    @Test void aPasswordChangeNeedsThreeFieldsAndAMatchingConfirmation() {
        assertThat(shapes.password(BASE + "password", Map.of("currentPassword", "old", "newPassword", "new", "confirmPassword", "new")))
                .isEqualTo(new SignInPasswordChange("old", "new")).hasToString("SignInPasswordChange[redacted]");
        for (Object body : bodies(Map.of(), Map.of("currentPassword", "old", "newPassword", "new"),
                Map.of("currentPassword", "old", "newPassword", "new", "confirmPassword", "other"),
                Map.of("currentPassword", 7, "newPassword", "new", "confirmPassword", "new"),
                Map.of("currentPassword", "old", "newPassword", 7, "confirmPassword", 7),
                Map.of("currentPassword", "old", "newPassword", "new", "confirmPassword", "new", "x", 1)))
            invalid(() -> shapes.password(BASE + "password", body));
        invalid(() -> shapes.password(BASE + "passwor%64", Map.of("currentPassword", "old", "newPassword", "new", "confirmPassword", "new")));
    }
    @Test void aSignInIdIsOneCanonicalLowerCaseUuid() {
        for (String route : List.of("replace", "revoke")) {
            assertThat(shapes.signInId(BASE + route, route, Map.of("signInId", ID))).isEqualTo(UUID.fromString(ID));
            for (Object body : bodies(Map.of(), Map.of("signInId", ID.toUpperCase(Locale.ROOT)), Map.of("signInId", "nope"), Map.of("signInId", 7),
                    Map.of("signInId", ID, "x", 1), Map.of("signInId", ID.substring(0, 35)), nullValue("signInId")))
                invalid(() -> shapes.signInId(BASE + route, route, body));
        }
        invalid(() -> shapes.signInId(BASE + "revok%65", "revoke", Map.of("signInId", ID)));
    }

    /** null, a list, a string and a number (no JSON object), then the given objects. */
    private static List<Object> bodies(Object... objects) {
        var all = new ArrayList<Object>(Arrays.asList(null, List.of(), "bad", 7));
        all.addAll(Arrays.asList(objects)); return all;
    }
    private static Map<String, Object> nullValue(String key) { var map = new HashMap<String, Object>(); map.put(key, null); return map; }
    private static void invalid(ThrowingCallable call) { refused(call, 400, "INVALID_SIGN_IN_REQUEST", "Invalid sign-in request."); }
    private static void refused(ThrowingCallable call, int status, String code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountFailure.class, failure -> {
            assertThat(failure.status()).isEqualTo(status); assertThat(failure.code()).isEqualTo(code); assertThat(failure).hasMessage(message);
        });
    }
}
