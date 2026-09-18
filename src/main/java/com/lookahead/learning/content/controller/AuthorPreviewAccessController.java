package com.lookahead.learning.content.controller;

import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.AuthorPreviewAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name = "app.deployment-environment", havingValue = "local")
@ConditionalOnProperty(name = "app.local-test.author-enabled", havingValue = "true")
public class AuthorPreviewAccessController {
    private final AuthorPreviewAccessService access;

    public AuthorPreviewAccessController(AuthorPreviewAccessService access) {
        this.access = access;
    }

    @GetMapping("/api/v1/author/previews/access")
    public ResponseEntity<Void> access(@AuthenticationPrincipal AccountPrincipal principal) {
        access.requireAccess(principal);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
