package com.lookahead.domain.cloud;

import com.lookahead.domain.security.IdentityUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

@Configuration
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
public class CloudSecurityConfiguration {
    @Bean CognitoSettings cognitoSettings(Environment env){return CognitoSettings.from(env);}
    @Bean CognitoIdentityProviderClient cognitoUserClient(CognitoSettings settings){
        return CognitoIdentityProviderClient.builder().region(Region.of(settings.region()))
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .endpointOverride(java.net.URI.create("https://cognito-idp."+settings.region()+".amazonaws.com"))
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(3)))
            .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(3))
                .retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.none()))
            .build();
    }
    @Bean CognitoTokenVerifier cognitoVerifier(CognitoSettings settings,CognitoIdentityProviderClient client){return new CognitoTokenVerifier(settings,client);}
    @Bean CloudSignInRegistry cloudSignIns(JdbcTemplate jdbc,PlatformTransactionManager manager){return new CloudSignInRegistry(jdbc,manager);}
    @Bean CloudDomainTokenConverter cloudAuthenticationConverter(CognitoTokenVerifier verifier,CloudSignInRegistry registry,HttpServletRequest request){return new CloudDomainTokenConverter(verifier,registry,request);}
    @Bean JwtDecoder cloudDecoder(CognitoSettings settings){
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(client);factory.setReadTimeout(Duration.ofSeconds(5));
        var decoder=NimbusJwtDecoder.withJwkSetUri(settings.issuer()+"/.well-known/jwks.json").restOperations(new RestTemplate(factory)).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(settings.issuer()));return decoder;
    }
    @Bean @Order(1) SecurityFilterChain cloudSignInSecurity(HttpSecurity http,JwtDecoder decoder,CognitoTokenVerifier verifier,CognitoSettings settings,HttpServletRequest request)throws Exception {
        return http.securityMatcher("/internal/v1/cloud-sign-ins/**")
            .csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.anyRequest().authenticated())
            .oauth2ResourceServer(r->r.jwt(j->j.decoder(decoder).jwtAuthenticationConverter(jwt->{
                String secret=request.getHeader("X-LookAhead-Gateway-Secret");
                // Compare every request, including absent credentials, before any
                // authentication can be returned. No header controls whether the
                // constant-time credential check executes.
                byte[] supplied=java.util.Objects.toString(secret,"").getBytes(StandardCharsets.UTF_8);
                boolean gatewayVerified=MessageDigest.isEqual(supplied,settings.gatewaySecret().getBytes(StandardCharsets.UTF_8));
                if(!gatewayVerified)throw CognitoTokenVerifier.invalid();
                return UsernamePasswordAuthenticationToken.authenticated(verifier.verify(jwt),null,List.of());
            })).authenticationEntryPoint((req,res,error)->{
                res.setStatus(error instanceof AuthenticationServiceException?503:401);res.setHeader("Cache-Control","no-store");res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Unable to verify sign-in.\"}");
            })).build();
    }
}
