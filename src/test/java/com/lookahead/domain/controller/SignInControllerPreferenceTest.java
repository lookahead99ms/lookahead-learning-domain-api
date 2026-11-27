package com.lookahead.domain.controller;

import com.lookahead.domain.client.SignInCognitoClient;
import com.lookahead.domain.security.token.SignInTokenVerifierTest;
import com.lookahead.domain.service.signin.SignInPasswordService;
import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.validator.SignInRequestValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignInControllerPreferenceTest {
    @Test void invalidPreferenceAndLinkShapesMatchLocalValidationWithoutCallingProvider() {
        var signInService=mock(SignInService.class,withSettings().mockMaker(MockMakers.SUBCLASS));
        // Use the interface proxy maker to avoid cross-maker handler ambiguity in the full suite.
        var provider=mock(CognitoIdentityProviderClient.class,
                withSettings().mockMaker(MockMakers.PROXY));
        var controller=new SignInController(signInService,new SignInPasswordService(signInService,new SignInCognitoClient(SignInTokenVerifierTest.TRUST,provider,provider)),new SignInRequestValidator());
        for(String action : new String[]{"preferences","links"}) {
            var request=new MockHttpServletRequest("POST","/internal/v1/cloud-sign-ins/"+action);
            for(Object body : new Object[]{null, List.of(), "bad", Map.of("unknown",true)}) {
                var failure=catchThrowableOfType(AccountFailure.class,()->{if(action.equals("preferences"))controller.preferences(null,body,request,new MockHttpServletResponse());else controller.links(null,body,request,new MockHttpServletResponse());});
                assertThat(failure).isNotNull();
                assertThat(failure.status()).isEqualTo(422);
            }
        }
        verifyNoInteractions(signInService,provider);
    }
}
