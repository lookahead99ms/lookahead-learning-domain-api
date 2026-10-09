package com.lookahead.domain.cloud;

import com.lookahead.domain.security.DomainSecurityConfiguration;
import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real signed JWT + security chains + controller + isolated PostgreSQL; Cognito GetUser is controlled. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CLOUD_AUTH_TEST_DATABASE_URL", matches=".+")
class CloudSecurityDatabaseTest extends CloudSignInRegistryDatabaseTest {
    static JdbcTemplate fixtureJdbc;static org.springframework.transaction.PlatformTransactionManager fixtureTransactions;
    static RSAKey key;static final AtomicBoolean providerRevoked=new AtomicBoolean();
    @Test void signedCloudTokensRequireGatewayAdmissionAndCurrentDomainProof()throws Exception {
        fixtureJdbc=jdbc;fixtureTransactions=new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        key=new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("synthetic-test-key").generate();providerRevoked.set(false);
        var identity=identity("http-owner");UUID owner=provision(identity);String proof=proof();
        try(var context=new AnnotationConfigWebApplicationContext()){
            context.setServletContext(new MockServletContext());context.getEnvironment().setActiveProfiles("accounts","resource");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cloud-fixture",Map.of("app.deployment-environment","dev","app.cognito.issuer",issuer,"app.cognito.region","us-east-2","app.cognito.client-id",CognitoTokenVerifierTest.SETTINGS.clientId(),"app.cognito.scope-prefix","lookahead","app.cognito.gateway-secret",CognitoTokenVerifierTest.SETTINGS.gatewaySecret())));
            context.register(Application.class);context.refresh();
            var http=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();String token=token(identity,Map.of());
            http.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token).header(CloudDomainTokenConverter.PROOF_HEADER,proof)).andExpect(status().isUnauthorized());
            http.perform(post("/internal/v1/cloud-sign-ins/admit").header("Authorization","Bearer "+token).header(CloudDomainTokenConverter.PROOF_HEADER,proof).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
            http.perform(post("/internal/v1/cloud-sign-ins/admit").header("Authorization","Bearer "+token).header(CloudDomainTokenConverter.PROOF_HEADER,proof).header("X-LookAhead-Gateway-Secret",CognitoTokenVerifierTest.SETTINGS.gatewaySecret()).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("data.accountId").value(owner.toString()));
            http.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token).header(CloudDomainTokenConverter.PROOF_HEADER,proof)).andExpect(status().isOk()).andExpect(jsonPath("owner").value(owner.toString())).andExpect(header().doesNotExist("Set-Cookie"));
            for(var overrides:List.of(Map.<String,Object>of("token_use","id"),Map.<String,Object>of("client_id","wrong"),Map.<String,Object>of("iss","https://wrong.example"),Map.<String,Object>of("exp",Date.from(Instant.now().minusSeconds(120))),Map.<String,Object>of("scope","openid"))){
                http.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token(identity,overrides)).header(CloudDomainTokenConverter.PROOF_HEADER,proof)).andExpect(status().isUnauthorized());
            }
            http.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token).header(CloudDomainTokenConverter.PROOF_HEADER,proof())).andExpect(status().isUnauthorized());
            providerRevoked.set(true);
            http.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token).header(CloudDomainTokenConverter.PROOF_HEADER,proof)).andExpect(status().isUnauthorized());
            providerRevoked.set(false);registry.terminate(identity,proof);
            http.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token).header(CloudDomainTokenConverter.PROOF_HEADER,proof)).andExpect(status().isUnauthorized());
        }
    }
    static String token(CognitoIdentity identity,Map<String,Object> overrides)throws Exception {
        var b=new JWTClaimsSet.Builder().issuer(identity.issuer()).subject(identity.subject()).issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("token_use","access").claim("client_id",CognitoTokenVerifierTest.SETTINGS.clientId()).claim("origin_jti",identity.family()).claim("auth_time",identity.authenticatedAt().getEpochSecond()).claim("scope","aws.cognito.signin.user.admin lookahead/account lookahead/content lookahead/support");
        overrides.forEach(b::claim);var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),b.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({DomainSecurityConfiguration.class,CloudSecurityConfiguration.class,CloudSignInController.class,AccountErrorHandler.class,Probe.class})
    static class Application {
        @Bean JdbcTemplate jdbc(){return fixtureJdbc;}
        @Bean org.springframework.transaction.PlatformTransactionManager transactions(){return fixtureTransactions;}
        @Bean @Primary JwtDecoder fixtureDecoder()throws Exception {var decoder=NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(CognitoTokenVerifierTest.SETTINGS.issuer()));return decoder;}
        @Bean @Primary CognitoIdentityProviderClient fixtureCognito(){
            var fixture=new CognitoFixture();
            fixture.response=request->{
                if(providerRevoked.get())throw NotAuthorizedException.builder().message("revoked").build();
                try {
                    var claims=SignedJWT.parse(request.accessToken()).getJWTClaimsSet();
                    return GetUserResponse.builder().username("learner").userAttributes(AttributeType.builder().name("sub").value(claims.getSubject()).build()).build();
                }catch(java.text.ParseException error){throw new IllegalStateException(error);}
            };return fixture.client;
        }
    }
    @RestController static class Probe {
        @GetMapping("/api/v1/auth/me") Map<String,String> who(@AuthenticationPrincipal AccountPrincipal principal){return Map.of("owner",principal.accountId().toString());}
    }
}
