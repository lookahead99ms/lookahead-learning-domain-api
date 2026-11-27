package com.lookahead.domain.security.config;

import com.lookahead.domain.config.AwsConnectionFactory;
import com.lookahead.domain.config.HttpConnectionFactory;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import com.lookahead.domain.client.SignInCognitoClient;
import com.lookahead.domain.security.handler.SignInSecurityHandler;
import com.lookahead.domain.security.token.SignInBffRequestConverter;
import com.lookahead.domain.security.token.SignInTokenConverter;
import com.lookahead.domain.security.token.SignInTokenSigningKeyInterceptor;
import com.lookahead.domain.security.token.SignInTokenVerifier;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.validator.SignInTokenTrustValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

@Configuration
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
public class SignInSecurityConfiguration {
    /** The sign-in settings, read and checked once in one pass; the two beans below are its two parts. */
    @Bean SignInTokenTrust.Checked signInCheckedValues(Environment env){return SignInTokenTrustValidator.validated(env);}
    @Bean SignInTokenTrust signInTokenTrust(SignInTokenTrust.Checked checked){return checked.trust();}
    @Bean SignInBffSecret signInBffSecret(SignInTokenTrust.Checked checked){return checked.bffSecret();}
    @Bean CognitoIdentityProviderClient cognitoUserClient(SignInTokenTrust trust){
        return AwsConnectionFactory.regional(CognitoIdentityProviderClient.builder(),"cognito-idp",trust.region(),AnonymousCredentialsProvider.create());
    }
    /** DLV-969: Disable, Enable and Delete reach the pool with the task role (IAM #7), never the token-check client. */
    @Bean(destroyMethod="") public CognitoIdentityProviderClient cognitoTaskRoleClient(SignInTokenTrust trust){
        return AwsConnectionFactory.regional(CognitoIdentityProviderClient.builder(),"cognito-idp",trust.region(),ContainerCredentialsProvider.builder().build());
    }
    /** Every Cognito call goes through this one client; it keeps both connections and uses each for the same calls as before. */
    @Bean SignInCognitoClient signInCognitoClient(SignInTokenTrust trust,CognitoIdentityProviderClient cognitoTaskRoleClient,CognitoIdentityProviderClient cognitoUserClient){
        return new SignInCognitoClient(trust,cognitoTaskRoleClient,cognitoUserClient);
    }
    @Bean SignInTokenVerifier signInTokenVerifier(SignInTokenTrust trust,SignInCognitoClient cognito){return new SignInTokenVerifier(trust,cognito);}
    @Bean SignInTokenConverter signInTokenConverter(SignInTokenVerifier verifier,SignInService signInService,HttpServletRequest request){return new SignInTokenConverter(verifier,signInService,request);}
    @Bean JwtDecoder signInDecoder(SignInTokenTrust trust){
        var factory=HttpConnectionFactory.noRedirects(Duration.ofSeconds(2),Duration.ofSeconds(5));
        var keys=new RestTemplate(factory);
        keys.getInterceptors().add(new SignInTokenSigningKeyInterceptor());
        var decoder=NimbusJwtDecoder.withJwkSetUri(trust.issuer()+"/.well-known/jwks.json").restOperations(keys).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(trust.issuer()));return decoder;
    }
    @Bean @Order(1) SecurityFilterChain signInSecurity(HttpSecurity http,JwtDecoder decoder,SignInTokenVerifier verifier,SignInBffSecret bffSecret,HttpServletRequest request)throws Exception {
        return http.securityMatcher("/internal/v1/cloud-sign-ins/**")
            .csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository())).httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.anyRequest().authenticated())
            .oauth2ResourceServer(r->r.jwt(j->j.decoder(decoder).jwtAuthenticationConverter(new SignInBffRequestConverter(verifier,bffSecret,request)))
                .authenticationEntryPoint(new SignInSecurityHandler())).build();
    }
}
