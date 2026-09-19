package com.lookahead.learning.content.dto;

import java.util.Set;
import java.util.UUID;

public record AccountView(UUID accountId, String username, String displayName, Set<String> topicGrants, Set<String> contentGrants, boolean authorPreview) {
    public AccountView(UUID accountId,String username,String displayName,Set<String> topicGrants){this(accountId,username,displayName,topicGrants,Set.of(),false);}
    public AccountView(UUID accountId,String username,String displayName,Set<String> topicGrants,Set<String> contentGrants){this(accountId,username,displayName,topicGrants,contentGrants,false);}
}
