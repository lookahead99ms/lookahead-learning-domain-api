package com.lookahead.domain.testaccounts;

import com.lookahead.domain.membership.MembershipService;
import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.handler.GlobalExceptionHandler;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
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
 * DLV-969 (2026-11-04): the exact JSON bodies the Web sends, through {@link TestAccountsController} with the JSON mapper
 * Spring Boot configures, each reaching the one service method it is for. Earlier tests called the services directly, so a
 * body the JSON reader refused (primitive flags a request did not send) shipped as "Request body is not valid JSON".
 */
public class TestAccountsControllerTest {
    static final AccountPrincipal OWNER = new AccountPrincipal(UUID.fromString("00000000-0000-4000-8000-0000000000aa"), "owner", "Owner", true, true);
    static final UUID ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    // Subclass mock maker: the image build runs without the inline agent.
    final TestAccountsService service = mock(TestAccountsService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final CreatedTestAccounts created = mock(CreatedTestAccounts.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final DevTestRoster roster = mock(DevTestRoster.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestAccountsController(service, created, roster))
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

    private org.springframework.test.web.servlet.ResultActions send(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test void restoreReachesTheRostersRestoreWithTheConfirmation() throws Exception {
        when(roster.restore(OWNER, new DevTestRoster.Restore("RESTORE"))).thenReturn(new DevTestRoster.Restored(2));
        send("/api/v1/admin/test-accounts", "{\"restoreRoster\":true,\"confirm\":\"RESTORE\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.restored").value(2)).andExpect(header().string("Cache-Control", "no-store"));
        verify(roster).restore(OWNER, new DevTestRoster.Restore("RESTORE"));
        verifyNoMoreInteractions(roster); verifyNoInteractions(service, created);
    }

    @Test void aPurchaseDecisionReachesDecidePurchase() throws Exception {
        var decision = new MembershipService.Decision(ID, "approve");
        when(service.decidePurchase(OWNER, decision)).thenReturn(JsonMapper.builder().build().createObjectNode().put("decision", "approve"));
        send("/api/v1/admin/test-accounts", "{\"purchaseDecision\":{\"purchaseId\":\"" + ID + "\",\"decision\":\"approve\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("approve"));
        verify(service).decidePurchase(OWNER, decision);
        verifyNoMoreInteractions(service); verifyNoInteractions(created, roster);
    }

    @Test void createTestAccountWithAKeyAndAScenarioReachesCreate() throws Exception {
        when(created.create(eq(OWNER), any())).thenReturn(new CreatedTestAccounts.Created(ID, "test-0123456789@lookahead.dev", true));
        send("/api/v1/admin/test-accounts", "{\"key\":\"" + ID + "\",\"scenario\":\"learner03\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountId").value(ID.toString())).andExpect(jsonPath("$.data.active").value(true));
        verify(created).create(eq(OWNER), argThat(create -> ID.equals(create.key()) && "learner03".equals(create.scenario())
                && !create.createsRoster() && !create.restoresRoster() && create.purchaseDecision() == null && create.password() == null));
        verifyNoMoreInteractions(created); verifyNoInteractions(service, roster);
    }

    @Test void theRosterFlagReachesTheRostersNextBatch() throws Exception {
        when(roster.next(OWNER)).thenReturn(new DevTestRoster.Status(27, 5, 0, 4, 4));
        send("/api/v1/admin/test-accounts", "{\"roster\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ready").value(5)).andExpect(jsonPath("$.data.changed").value(4));
        verify(roster).next(OWNER);
        verifyNoMoreInteractions(roster); verifyNoInteractions(service, created);
    }

    @Test void falseOrNullFlagsAreACreateAndABrokenBodyReachesNoService() throws Exception {
        when(created.create(eq(OWNER), any())).thenReturn(new CreatedTestAccounts.Created(ID, "test-0123456789@lookahead.dev", true));
        send("/api/v1/admin/test-accounts", "{\"key\":\"" + ID + "\",\"roster\":false,\"restoreRoster\":null}").andExpect(status().isOk());
        verify(created).create(eq(OWNER), argThat(create -> ID.equals(create.key())));
        verifyNoInteractions(service, roster);
        clearInvocations(created);
        for (String broken : new String[]{"{\"roster\":", "{\"roster\":\"perhaps\"}", "{\"key\":\"not-a-uuid\"}"})
            send("/api/v1/admin/test-accounts", broken).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        verifyNoInteractions(service, roster, created);
    }

    /** The owner's roster card reads {@code roster.changed} from the allowance to grey out "Reset all". */
    @Test void theAllowanceCarriesTheRostersChangedCount() throws Exception {
        when(created.allowance(OWNER)).thenReturn(new CreatedTestAccounts.Allowance(10, 1, 9));
        when(roster.statusFor(OWNER)).thenReturn(new DevTestRoster.Status(27, 27, 0, 0, 3));
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
        verify(service).reset(OWNER, ID, new TestAccountsService.Reset(ID, 3L, "learner01@lookahead.dev", "scenario"));
        send("/api/v1/admin/test-accounts/" + ID + "/reset", "{\"key\":\"" + ID + "\",\"confirmation\":\"learner01@lookahead.dev\",\"preset\":\"scenario\"}")
                .andExpect(status().isOk());
        verify(service).reset(OWNER, ID, new TestAccountsService.Reset(ID, null, "learner01@lookahead.dev", "scenario"));
        org.assertj.core.api.Assertions.assertThat(new TestAccountsService.Reset(ID, null, "x", "scenario").expects(1)).isFalse();
        org.assertj.core.api.Assertions.assertThat(new TestAccountsService.Reset(ID, 1L, "x", "scenario").expects(1)).isTrue();
        org.assertj.core.api.Assertions.assertThat(new TestAccountsService.Reset(ID, 2L, "x", "scenario").expects(1)).isFalse();
    }
}
