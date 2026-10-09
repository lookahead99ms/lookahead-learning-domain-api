package com.lookahead.domain.cloud;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

final class CognitoFixture {
    final AtomicInteger calls=new AtomicInteger();
    Function<GetUserRequest,GetUserResponse> response=request->{throw new IllegalStateException("No fixture");};
    Runnable changePassword=()->{};
    Runnable globalSignOut=()->{};
    final CognitoIdentityProviderClient client=(CognitoIdentityProviderClient)Proxy.newProxyInstance(CognitoIdentityProviderClient.class.getClassLoader(),new Class<?>[]{CognitoIdentityProviderClient.class},(proxy,method,args)->{
        if(method.getName().equals("getUser")){
            calls.incrementAndGet();GetUserRequest request;
            if(args[0] instanceof GetUserRequest value)request=value;
            else {var builder=GetUserRequest.builder();((java.util.function.Consumer<GetUserRequest.Builder>)args[0]).accept(builder);request=builder.build();}
            return response.apply(request);
        }
        if(method.getName().equals("changePassword")){changePassword.run();return ChangePasswordResponse.builder().build();}
        if(method.getName().equals("globalSignOut")){globalSignOut.run();return GlobalSignOutResponse.builder().build();}
        if(method.getName().equals("close"))return null;
        if(method.getName().equals("serviceName"))return "cognito-idp";
        if(method.getName().equals("toString"))return "CognitoFixture";
        if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
        if(method.getName().equals("equals"))return proxy==args[0];
        throw new java.lang.UnsupportedOperationException(method.getName());
    });
}
