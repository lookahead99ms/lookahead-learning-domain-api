package com.lookahead.domain.controller;

import com.lookahead.domain.dto.TestAccountReset;
import com.lookahead.domain.dto.MembershipPurchaseDecision;
import com.lookahead.domain.service.testaccounts.TestAccountAdminService;
import com.lookahead.domain.service.testaccounts.TestAccountCreationService;
import com.lookahead.domain.service.testaccounts.TestAccountDevRosterService;
import com.lookahead.domain.service.testaccounts.TestAccountService;
import com.lookahead.domain.security.handler.AccountErrorHandler;
import com.lookahead.domain.security.handler.GlobalExceptionHandler;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.validator.TestAccountCreateRequestValidator;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DLV-969 (2026-11-04): the exact JSON bodies the Web sends, through {@link TestAccountController} with the JSON mapper
 * Spring Boot configures, each reaching the one service method it is for. Earlier tests called the services directly, so a
 * body the JSON reader refused (primitive flags a request did not send) shipped as "Request body is not valid JSON".
 */
public class TestAccountControllerTest {
    static final AccountPrincipal OWNER = new AccountPrincipal(UUID.fromString("00000000-0000-4000-8000-0000000000aa"), "owner", "Owner", true, true);
    static final UUID ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    // Subclass mock maker: the image build runs without the inline agent.
    final TestAccountService service = mock(TestAccountService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final TestAccountCreationService creation = mock(TestAccountCreationService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final TestAccountDevRosterService roster = mock(TestAccountDevRosterService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestAccountController(service, creation, new TestAccountAdminService(service, creation, roster), new TestAccountCreateRequestValidator()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(bootJsonMapper()))
            .setControllerAdvice(new AccountErrorHandler(), new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == AccountPrincipal.class; }
                public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest request, WebDataBinderFactory binders) { return OWNER; }
            }).build();

    /** The application's JSON mapper: Spring Boot's Jackson auto-configuration, nothing set by hand. */
    public static JsonMapper bootJsonMapper() {
        var mapper = new JsonMapper[1];
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper[0] = context.getBean(JsonMapper.class));
        return mapper[0];
    }

    private ResultActions send(String path, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test void restoreReachesTheRostersRestoreWithTheConfirmation() throws Exception {
        when(roster.restore(OWNER, new TestAccountDevRosterService.Restore("RESTORE"))).thenReturn(new TestAccountDevRosterService.Restored(2));
        send("/api/v1/admin/test-accounts", "{\"restoreRoster\":true,\"confirm\":\"RESTORE\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.restored").value(2)).andExpect(header().string("Cache-Control", "no-store"));
        verify(roster).restore(OWNER, new TestAccountDevRosterService.Restore("RESTORE"));
        verifyNoMoreInteractions(roster); verifyNoInteractions(service, creation);
    }

    @Test void aPurchaseDecisionReachesDecidePurchase() throws Exception {
        var decision = new MembershipPurchaseDecision(ID, "approve");
        when(service.decidePurchase(OWNER, decision)).thenReturn(JsonMapper.builder().build().createObjectNode().put("decision", "approve"));
        send("/api/v1/admin/test-accounts", "{\"purchaseDecision\":{\"purchaseId\":\"" + ID + "\",\"decision\":\"approve\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("approve"));
        verify(service).decidePurchase(OWNER, decision);
        verifyNoMoreInteractions(service); verifyNoInteractions(creation, roster);
    }

    @Test void createTestAccountWithAKeyAndAScenarioReachesCreate() throws Exception {
        when(creation.create(eq(OWNER), any())).thenReturn(new TestAccountCreationService.Created(ID, "test-0123456789@lookahead.dev", true));
        send("/api/v1/admin/test-accounts", "{\"key\":\"" + ID + "\",\"scenario\":\"learner03\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountId").value(ID.toString())).andExpect(jsonPath("$.data.active").value(true));
        verify(creation).create(eq(OWNER), argThat(create -> ID.equals(create.key()) && "learner03".equals(create.scenario())
                && !create.createsRoster() && !create.restoresRoster() && create.purchaseDecision() == null && create.password() == null));
        verifyNoMoreInteractions(creation); verifyNoInteractions(service, roster);
    }

    @Test void theRosterFlagReachesTheRostersNextBatch() throws Exception {
        when(roster.next(OWNER)).thenReturn(new TestAccountDevRosterService.Status(27, 5, 0, 4, 4));
        send("/api/v1/admin/test-accounts", "{\"roster\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ready").value(5)).andExpect(jsonPath("$.data.changed").value(4));
        verify(roster).next(OWNER);
        verifyNoMoreInteractions(roster); verifyNoInteractions(service, creation);
    }

    @Test void falseOrNullFlagsAreACreateAndABrokenBodyReachesNoService() throws Exception {
        when(creation.create(eq(OWNER), any())).thenReturn(new TestAccountCreationService.Created(ID, "test-0123456789@lookahead.dev", true));
        send("/api/v1/admin/test-accounts", "{\"key\":\"" + ID + "\",\"roster\":false,\"restoreRoster\":null}").andExpect(status().isOk());
        verify(creation).create(eq(OWNER), argThat(create -> ID.equals(create.key())));
        verifyNoInteractions(service, roster);
        clearInvocations(creation);
        for (String broken : new String[]{"{\"roster\":", "{\"roster\":\"perhaps\"}", "{\"key\":\"not-a-uuid\"}"})
            send("/api/v1/admin/test-accounts", broken).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        verifyNoInteractions(service, roster, creation);
    }

    /** The owner's roster card reads {@code roster.changed} from the allowance to grey out "Reset all". */
    @Test void theAllowanceCarriesTheRostersChangedCount() throws Exception {
        when(creation.allowance(OWNER)).thenReturn(new TestAccountCreationService.Allowance(10, 1, 9));
        when(roster.statusFor(OWNER)).thenReturn(new TestAccountDevRosterService.Status(27, 27, 0, 0, 3));
        mvc.perform(get("/api/v1/admin/test-accounts/allowance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roster.total").value(27)).andExpect(jsonPath("$.data.roster.ready").value(27))
                .andExpect(jsonPath("$.data.roster.deleted").value(0)).andExpect(jsonPath("$.data.roster.withoutPlan").value(0))
                .andExpect(jsonPath("$.data.roster.changed").value(3)).andExpect(jsonPath("$.data.remaining").value(9));
    }

    /** Reset sends every field today; a body without {@code expectedRevision} still reaches the service, which answers 428. */
    @Test void resetBodiesReachResetWithOrWithoutTheirRevision() throws Exception {
        send("/api/v1/admin/test-accounts/" + ID + "/reset",
                "{\"key\":\"" + ID + "\",\"expectedRevision\":3,\"confirmation\":\"learner01@lookahead.dev\",\"preset\":\"scenario\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reset").value(true));
        verify(service).reset(OWNER, ID, new TestAccountReset(ID, 3L, "learner01@lookahead.dev", "scenario"));
        send("/api/v1/admin/test-accounts/" + ID + "/reset", "{\"key\":\"" + ID + "\",\"confirmation\":\"learner01@lookahead.dev\",\"preset\":\"scenario\"}")
                .andExpect(status().isOk());
        verify(service).reset(OWNER, ID, new TestAccountReset(ID, null, "learner01@lookahead.dev", "scenario"));
        Assertions.assertThat(new TestAccountReset(ID, null, "x", "scenario").expects(1)).isFalse();
        Assertions.assertThat(new TestAccountReset(ID, 1L, "x", "scenario").expects(1)).isTrue();
        Assertions.assertThat(new TestAccountReset(ID, 2L, "x", "scenario").expects(1)).isFalse();
    }
}
