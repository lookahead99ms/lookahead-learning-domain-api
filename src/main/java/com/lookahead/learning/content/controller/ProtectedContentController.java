package com.lookahead.learning.content.controller;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.ProtectedContentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("accounts")
public class ProtectedContentController {
    private final ProtectedContentService content;
    public ProtectedContentController(ProtectedContentService content){this.content=content;}
    @GetMapping("/content/**") public ResponseEntity<byte[]> asset(HttpServletRequest request,@AuthenticationPrincipal AccountPrincipal principal) {
        var result=content.read(request.getRequestURI(),principal);
        return ResponseEntity.ok().header("Content-Type",result.mediaType()).header("Cache-Control","no-store, private")
            .header("X-Content-Type-Options","nosniff").header("X-Content-Publication",result.publicationVersion()).body(result.bytes());
    }
}
