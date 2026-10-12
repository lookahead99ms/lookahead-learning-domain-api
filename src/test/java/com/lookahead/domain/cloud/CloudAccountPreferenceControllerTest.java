package com.lookahead.domain.cloud;

import com.lookahead.learning.content.exception.AccountFailure;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudAccountPreferenceControllerTest {
    @Test void invalidPreferenceAndLinkShapesMatchLocalValidationWithoutCallingProvider() {
        var registry=mock(CloudSignInRegistry.class);
        // Use the interface proxy maker to avoid cross-maker handler ambiguity in the full suite.
        var provider=mock(software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient.class,
                withSettings().mockMaker(org.mockito.MockMakers.PROXY));
        var controller=new CloudSignInController(registry,provider);
        for(String action : new String[]{"preferences","links"}) {
            var request=new MockHttpServletRequest("POST","/internal/v1/cloud-sign-ins/"+action);
            for(Object body : new Object[]{null, java.util.List.of(), "bad", Map.of("unknown",true)}) {
                var failure=catchThrowableOfType(AccountFailure.class,()->controller.mutate(null,body,request,new MockHttpServletResponse()));
                assertThat(failure).isNotNull();
                assertThat(failure.status()).isEqualTo(422);
            }
        }
        verifyNoInteractions(registry,provider);
    }
}
