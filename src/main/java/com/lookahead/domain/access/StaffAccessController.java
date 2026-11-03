package com.lookahead.domain.access;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class StaffAccessController {
    /**
     * What a co-author sees of a saved assignment (DLV-969): another person's role label, never their account id or
     * invitation email. Their own row stays theirs: their account id and their own name.
     */
    public record SharedAssignment(UUID id,UUID accountId,String pendingEmail,String label,boolean pending,
        Set<String> roles,Set<String> topicGrants,boolean fullCurriculum,long revision) {}
    private final StaffAccessService access;
    private final AccountDirectory directory;
    private final StaffAliases aliases;
    public StaffAccessController(StaffAccessService access, AccountDirectory directory, StaffAliases aliases){this.access=access;this.directory=directory;this.aliases=aliases;}
    @GetMapping("/api/v1/account/permissions")
    public ApiResponse<?> permissions(@AuthenticationPrincipal AccountPrincipal actor,HttpServletResponse response){noStore(response);return ApiResponse.success(access.permissions(actor));}
    @GetMapping("/api/v1/admin/access")
    public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal actor,HttpServletResponse response){
        noStore(response);var rows=access.list(actor);
        if(aliases.seesIdentities(actor))return ApiResponse.success(rows);
        return ApiResponse.success(rows.stream().map(row->{
            boolean own=actor.accountId().equals(row.accountId());
            String label=row.accountId()==null?"Pending invitation":own?ownName(actor):aliases.label(row.accountId());
            return new SharedAssignment(row.id(),own?row.accountId():null,null,label,row.accountId()==null,
                row.roles(),row.topicGrants(),row.fullCurriculum(),row.revision());
        }).toList());
    }
    @GetMapping("/api/v1/admin/access/catalog")
    public ApiResponse<?> catalog(@AuthenticationPrincipal AccountPrincipal actor,HttpServletResponse response){noStore(response);return ApiResponse.success(access.catalog(actor));}
    /** Owner only since DLV-969: finding a person by email or username reveals who they are. */
    @PostMapping("/api/v1/admin/access/lookup")
    public ApiResponse<?> lookup(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody Map<String,String> request,HttpServletResponse response){noStore(response);access.requireOwner(actor);return ApiResponse.success(directory.lookup(request.get("query")));}
    @PostMapping("/api/v1/admin/access")
    public ApiResponse<?> save(@AuthenticationPrincipal AccountPrincipal actor,@RequestBody StaffAccessService.Change request,HttpServletResponse response){noStore(response);return ApiResponse.success(access.save(actor,request));}
    private static String ownName(AccountPrincipal actor){
        String name=actor.displayName();
        return name==null||name.isBlank()?actor.getUsername():name.strip();
    }
    private static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
}
