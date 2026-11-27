package com.lookahead.domain.validator;

import com.lookahead.domain.util.CanonicalUuid;
import com.lookahead.domain.dto.SignInPasswordChange;
import com.lookahead.domain.exception.AccountFailure;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The shape of a request on the internal sign-in routes ({@code /internal/v1/cloud-sign-ins/…}): how its address is
 * written and what its JSON body holds. DLV-1016: these checks were in SignInController's one handler for all the
 * routes and moved here word for word, in the same order: the address, then "is the body an object", then the fields.
 * A wrong shape is 400 {@code INVALID_SIGN_IN_REQUEST}, except on preferences and links, which keep their own 422.
 */
@Component
public class SignInRequestValidator {
    /**
     * True when the request address ends with the route's own name, written plainly. The route matcher also accepts a
     * percent-encoded spelling (…/adm%69t); the checks below refuse that, as the single handler did.
     */
    public boolean plain(String address, String route) { return address.endsWith("/" + route); }

    /** A route that takes no fields (admit, cancel, revoke-others, logout): the body is an empty object. */
    public void empty(String address, String route, Object payload) {
        if (!body(address, route, payload).isEmpty()) throw invalid();
    }

    public String displayName(String address, Object payload) {
        Map<String,Object> body = body(address, "profile", payload);
        if(!body.keySet().equals(Set.of("displayName"))||!(body.get("displayName") instanceof String name))throw invalid();
        return name;
    }

    public String theme(String address, Object payload) {
        if (!plain(address, "preferences")) throw invalid();
        if (!(payload instanceof Map<?,?>)) throw new AccountFailure(422,"INVALID_PREFERENCES","Choose light, dark or system.");
        Map<String,Object> body = map(payload);
        if(!body.keySet().equals(Set.of("theme"))||!(body.get("theme") instanceof String theme))throw new AccountFailure(422,"INVALID_PREFERENCES","Choose light, dark or system.");
        return theme;
    }

    /** The four profile links in their fixed order; a cleared link is null. The links themselves are checked by {@link AccountProfileLinkValidator}. */
    public Map<String,String> links(String address, Object payload) {
        if (!plain(address, "links")) throw invalid();
        if (!(payload instanceof Map<?,?>)) throw AccountProfileLinkValidator.invalidShape();
        Map<String,Object> body = map(payload);
        if(!body.keySet().equals(Set.copyOf(AccountProfileLinkValidator.KINDS)))throw AccountProfileLinkValidator.invalidShape();
        var links=new LinkedHashMap<String,String>();
        for(String kind:AccountProfileLinkValidator.KINDS){Object value=body.get(kind);if(value!=null&&!(value instanceof String))throw AccountProfileLinkValidator.invalidShape();links.put(kind,(String)value);}
        return links;
    }

    public SignInPasswordChange password(String address, Object payload) {
        Map<String,Object> body = body(address, "password", payload);
        if(!body.keySet().equals(Set.of("currentPassword","newPassword","confirmPassword"))||!(body.get("currentPassword") instanceof String old)||!(body.get("newPassword") instanceof String next)||!next.equals(body.get("confirmPassword")))throw invalid();
        return new SignInPasswordChange(old, next);
    }

    /** The one sign-in a replace or a revoke names: {@code signInId} only, a UUID in its canonical lower-case form. */
    public UUID signInId(String address, String route, Object payload) {
        Map<String,Object> body = body(address, route, payload);
        if(!body.keySet().equals(Set.of("signInId"))||!(body.get("signInId") instanceof String id))throw invalid();
        UUID value=CanonicalUuid.parse(id);if(value==null)throw invalid();return value;
    }

    public String label(String address, Object payload) {
        Map<String,Object> body = body(address, "label", payload);
        if(!body.keySet().equals(Set.of("label"))||!(body.get("label") instanceof String label))throw invalid();
        return label;
    }

    /** The body as an object, for a plainly written address; anything else is refused. */
    private Map<String,Object> body(String address, String route, Object payload) {
        if (!plain(address, route)) throw invalid();
        if (!(payload instanceof Map<?,?>)) throw invalid();
        return map(payload);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object payload) { return (Map<String,Object>)payload; }
    private static AccountFailure invalid(){return new AccountFailure(400,"INVALID_SIGN_IN_REQUEST","Invalid sign-in request.");}
}
