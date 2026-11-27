package com.lookahead.domain.controller;

import com.lookahead.domain.security.handler.AccountErrorHandler;
import com.lookahead.domain.security.handler.GlobalExceptionHandler;
import com.lookahead.domain.service.signin.SignInPasswordService;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.validator.SignInRequestValidator;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DLV-1016: each of the fourteen sign-in routes has its own method and reaches its one service call; before, one
 * handler chose by the address. The answers are the ones that handler gave, including for an address that is not
 * written plainly.
 */
class SignInControllerTest {
    static final String BASE = "/internal/v1/cloud-sign-ins/";
    static final UUID ID = UUID.fromString("5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d");
    final SignInService signIns = mock(SignInService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final SignInPasswordService passwords = mock(SignInPasswordService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SignInController(signIns, passwords, new SignInRequestValidator()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(TestAccountControllerTest.bootJsonMapper()))
            .setControllerAdvice(new AccountErrorHandler(), new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build(); // nobody signed in: the identity is null

    private ResultActions send(String route, String body) throws Exception {
        return mvc.perform(post(URI.create(BASE + route)).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-LookAhead-SignIn-Proof", "proof").header("X-LookAhead-Challenge", "challenge").header("Authorization", "Bearer access-token"));
    }
    private ResultActions read(String route) throws Exception {
        return mvc.perform(get(URI.create(BASE + route)).header("X-LookAhead-SignIn-Proof", "proof").header("X-LookAhead-Challenge", "challenge"));
    }

    @Test void eachReadReachesItsOwnServiceCallAndIsNeverCached() throws Exception {
        read("inventory").andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(signIns).inventory(null, "proof");
        read("challenge").andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(signIns).challenge(null, "proof", "challenge");
        read("profile").andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(signIns).profile(null, "proof", null);
        verifyNoMoreInteractions(signIns);
    }
    /** As the single handler did: a challenge or profile read whose address is percent-encoded answers the inventory. */
    @Test void aReadWhoseAddressIsNotWrittenPlainlyAnswersTheInventory() throws Exception {
        read("challeng%65").andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        read("profil%65").andExpect(status().isOk());
        read("inventor%79").andExpect(status().isOk());
        verify(signIns, times(3)).inventory(null, "proof");
        verifyNoMoreInteractions(signIns);
    }
    @Test void eachRouteWithoutFieldsReachesItsOwnServiceCall() throws Exception {
        send("admit", "{}").andExpect(status().isOk());
        verify(signIns).admit(null, "proof");
        send("cancel", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelled").value(true));
        verify(signIns).cancel(null, "proof", "challenge");
        send("revoke-others", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.reauthenticationRequired").value(false));
        verify(signIns).revokeOthers(null, "proof");
        send("logout", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.terminated").value(true));
        verify(signIns).terminate(null, "proof");
        verifyNoMoreInteractions(signIns);
        verifyNoInteractions(passwords);
    }
    @Test void eachRouteWithFieldsHandsItsCheckedValueToItsOwnServiceCall() throws Exception {
        when(signIns.revoke(null, "proof", ID)).thenReturn(true);
        send("replace", "{\"signInId\":\"" + ID + "\"}").andExpect(status().isOk());
        verify(signIns).replace(null, "proof", "challenge", ID);
        send("revoke", "{\"signInId\":\"" + ID + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.reauthenticationRequired").value(true));
        verify(signIns).revoke(null, "proof", ID);
        send("label", "{\"label\":\"Laptop\"}").andExpect(status().isOk());
        verify(signIns).rename(null, "proof", "Laptop");
        send("profile", "{\"displayName\":\"Ada\"}").andExpect(status().isOk());
        verify(signIns).profile(null, "proof", "Ada");
        send("preferences", "{\"theme\":\"dark\"}").andExpect(status().isOk());
        verify(signIns).preferences(null, "proof", "dark");
        send("links", "{\"website\":\"w\",\"x\":null,\"linkedin\":\"l\",\"github\":\"g\"}").andExpect(status().isOk());
        var links = new LinkedHashMap<String, String>(); links.put("github", "g"); links.put("linkedin", "l"); links.put("x", null); links.put("website", "w");
        verify(signIns).links(null, "proof", links);
        verifyNoMoreInteractions(signIns);
    }
    @Test void aPasswordChangeHandsTheServiceBothPasswordsAndTheCallersAccessToken() throws Exception {
        send("password", "{\"currentPassword\":\"old\",\"newPassword\":\"new\",\"confirmPassword\":\"new\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reauthenticationRequired").value(true)).andExpect(header().string("Cache-Control", "no-store"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Supplier<String>> token = ArgumentCaptor.forClass(Supplier.class);
        verify(passwords).change(isNull(), eq("proof"), eq("old"), eq("new"), token.capture());
        assertThat(token.getValue()).isNotNull();
        verifyNoInteractions(signIns);
    }
    @Test void aWrongShapeIsRefusedBeforeAnyServiceCallWithTheRoutesOwnRefusal() throws Exception {
        for (String route : new String[]{"admit", "replace", "cancel", "revoke", "revoke-others", "label", "logout", "profile", "password"})
            send(route, "{\"unknown\":true}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIGN_IN_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Invalid sign-in request.")).andExpect(header().string("Cache-Control", "no-store"));
        send("preferences", "[]").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_PREFERENCES"));
        send("links", "[]").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_PROFILE_LINK"));
        // A percent-encoded address reaches the route's method and is refused there, whatever the body.
        for (String route : new String[]{"adm%69t", "preference%73", "link%73", "logou%74"})
            send(route, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIGN_IN_REQUEST"));
        verifyNoInteractions(signIns, passwords);
    }
    @Test void aRouteAnswersOnlyItsOwnMethod() throws Exception {
        mvc.perform(get(BASE + "admit")).andExpect(status().isMethodNotAllowed());
        send("inventory", "{}").andExpect(status().isMethodNotAllowed());
        send("unknown", "{}").andExpect(status().isNotFound());
        verifyNoInteractions(signIns, passwords);
    }
}
