package com.lookahead.learning.content.service;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProtectedContentServiceTest {
    @TempDir Path directory;
    private final JsonMapper mapper=new JsonMapper();
    private final AccountRepository accounts=mock(AccountRepository.class);
    private final AccountPrincipal learner=new AccountPrincipal(UUID.randomUUID(),"learner","Learner", true);
    private static final List<String> BUNDLE_SCOPES=List.of("learn:sample-course","grow:another-course");
    private ProtectedContentPolicy policy() throws Exception {
        var entries=new ArrayList<Map<String,Object>>();
        for(String tier:List.of("public","free","pro")) {
            byte[] bytes=("{\"title\":\""+tier+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(directory.resolve(tier+".json"),bytes);
            entries.add(Map.of("path","/content/"+tier+".json","sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),"mediaType","application/json","tier",tier,"scopes",List.of("learn:sample-course"),"contentIds",List.of(tier+"-lesson")));
        }
        return policy(entries);
    }
    private ProtectedContentPolicy policy(List<Map<String,Object>> entries) throws Exception {
        Files.writeString(directory.resolve("manifest.json"),mapper.writeValueAsString(Map.of("schemaVersion","content-publication/v1","version","test-v1","assets",entries)));
        return new ProtectedContentPolicy(mapper,directory.resolve("manifest.json").toString(),directory.toString());
    }
    private Map<String,Object> asset(String path,String tier,List<String> contentIds) throws Exception {
        byte[] bytes="{\"fixture\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file=directory.resolve(path.substring("/content/".length()));
        Files.createDirectories(file.getParent());Files.write(file,bytes);
        return new HashMap<>(Map.of("path",path,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "mediaType","application/json","tier",tier,"scopes",BUNDLE_SCOPES,"contentIds",contentIds));
    }
    private void denied(Runnable call,int status,String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AccountFailure.class,error->{assertThat(error.status()).isEqualTo(status);assertThat(error.code()).isEqualTo(code);});
    }
    @Test void publicIsAnonymousButFreeRequiresAnEnabledCurrentAccount() throws Exception {
        var service=new ProtectedContentService(policy(),accounts);
        assertThat(service.read("/content/public.json",null).mediaType()).isEqualTo("application/json");
        denied(()->service.read("/content/free.json",null),401,"AUTHENTICATION_REQUIRED");
        when(accounts.isEnabled(learner.accountId())).thenReturn(true);
        assertThat(service.read("/content/free.json",learner).bytes()).isNotEmpty();
        when(accounts.isEnabled(learner.accountId())).thenReturn(false);
        denied(()->service.read("/content/free.json",learner),401,"AUTHENTICATION_REQUIRED");
    }
    @Test void courseGrantIsRecheckedOnEveryReadAndCannotUnlockOtherCourses() throws Exception {
        var service=new ProtectedContentService(policy(),accounts);
        when(accounts.isEnabled(learner.accountId())).thenReturn(true);
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of("grow:another-course"));
        denied(()->service.read("/content/pro.json",learner),403,"CONTENT_SCOPE_REQUIRED");
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of("learn:sample-course"));
        assertThat(service.read("/content/pro.json",learner).bytes()).isNotEmpty();
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of());
        denied(()->service.read("/content/pro.json",learner),403,"CONTENT_SCOPE_REQUIRED");
    }
    @Test void excludesUnlistedFilesAndRejectsTraversalAndChangedBytes() throws Exception {
        var policy=policy();var service=new ProtectedContentService(policy,accounts);
        for(String path:List.of("/content/manifest.json","/content/../public.json","/content/%2e%2e/public.json","/content/public.json/","/content/public.json%00"))denied(()->service.read(path,null),404,"CONTENT_NOT_FOUND");
        assertThat(policy.freeContentIds()).containsExactlyInAnyOrder("free-lesson","public-lesson");
        Files.writeString(directory.resolve("public.json"),"changed");
        denied(()->service.read("/content/public.json",null),503,"CONTENT_UNAVAILABLE");
    }
    @Test void symlinkReplacementCannotCrossThePublicationBoundary() throws Exception {
        var service=new ProtectedContentService(policy(),accounts);
        Files.delete(directory.resolve("public.json"));
        Files.createSymbolicLink(directory.resolve("public.json"),directory.resolve("free.json"));
        denied(()->service.read("/content/public.json",null),503,"CONTENT_UNAVAILABLE");
    }
    @Test void disabledPublicationServesNothingAndPartialConfigurationFailsClosed() {
        var disabled=new ProtectedContentPolicy(mapper,"","");
        assertThat(disabled.find("/content/public.json")).isEmpty();
        assertThat(disabled.contentGrants(Set.copyOf(BUNDLE_SCOPES))).isEmpty();
        denied(()->new ProtectedContentService(disabled,accounts).read("/content/study-plans/index.json",learner),404,"CONTENT_NOT_FOUND");
        verifyNoInteractions(accounts);
        assertThatThrownBy(()->new ProtectedContentPolicy(mapper,"",directory.toString())).isInstanceOf(IllegalStateException.class);
    }
    @Test void allScopesRequireAnEnabledAccountAndCurrentFullGrantsOnEveryRead() throws Exception {
        String path="/content/study-plans/templates/sample-template.json";
        var entry=asset(path,"pro",List.of());entry.put("scopeMatch","all");
        var service=new ProtectedContentService(policy(List.of(entry)),accounts);
        denied(()->service.read(path,null),401,"AUTHENTICATION_REQUIRED");
        when(accounts.isEnabled(learner.accountId())).thenReturn(true);
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of(BUNDLE_SCOPES.getFirst()));
        denied(()->service.read(path,learner),403,"CONTENT_SCOPE_REQUIRED");
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.copyOf(BUNDLE_SCOPES));
        assertThat(service.read(path,learner).bytes()).isNotEmpty();
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of(BUNDLE_SCOPES.getLast()));
        denied(()->service.read(path,learner),403,"CONTENT_SCOPE_REQUIRED");
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of());
        denied(()->service.read(path,learner),403,"CONTENT_SCOPE_REQUIRED");
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.copyOf(BUNDLE_SCOPES));
        when(accounts.isEnabled(learner.accountId())).thenReturn(false);
        denied(()->service.read(path,learner),401,"AUTHENTICATION_REQUIRED");
    }
    @Test void omittedAndExplicitAnyScopesRetainPartialGrantAccess() throws Exception {
        for(boolean explicit:List.of(false,true)) {
            var entry=asset("/content/legacy.json","pro",List.of("legacy-lesson"));
            if(explicit)entry.put("scopeMatch","any");
            var policy=policy(List.of(entry));
            assertThat(policy.find("/content/legacy.json").orElseThrow().scopeMatch()).isEqualTo("any");
            var service=new ProtectedContentService(policy,accounts);
            when(accounts.isEnabled(learner.accountId())).thenReturn(true);
            for(String scope:BUNDLE_SCOPES) {
                when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of(scope));
                assertThat(service.read("/content/legacy.json",learner).bytes()).isNotEmpty();
                assertThat(policy.contentGrants(Set.of(scope))).containsExactly("legacy-lesson");
            }
            when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of("look-ahead:unrelated-course"));
            denied(()->service.read("/content/legacy.json",learner),403,"CONTENT_SCOPE_REQUIRED");
        }
    }
    @Test void contentGrantsNeverUnlockAllScopeBundleFromOneCourseAndReevaluateRevocation() throws Exception {
        var all=asset("/content/bundle.json","pro",List.of("bundle-first","bundle-second"));all.put("scopeMatch","all");
        var any=asset("/content/legacy.json","pro",List.of("legacy-lesson"));
        var free=asset("/content/free.json","free",List.of("free-lesson"));free.put("scopeMatch","all");
        var policy=policy(List.of(all,any,free));
        assertThat(policy.contentGrants(Set.of())).containsExactly("free-lesson");
        assertThat(policy.contentGrants(Set.of(BUNDLE_SCOPES.getFirst()))).containsExactlyInAnyOrder("free-lesson","legacy-lesson");
        assertThat(policy.contentGrants(Set.copyOf(BUNDLE_SCOPES))).containsExactlyInAnyOrder("free-lesson","legacy-lesson","bundle-first","bundle-second");
        assertThat(policy.contentGrants(Set.of(BUNDLE_SCOPES.getLast()))).containsExactlyInAnyOrder("free-lesson","legacy-lesson");
    }
    @Test void allScopeMatchingDoesNotChangePublicOrFreeAuthentication() throws Exception {
        var publicEntry=asset("/content/public.json","public",List.of("public-lesson"));publicEntry.put("scopeMatch","all");
        var freeEntry=asset("/content/free.json","free",List.of("free-lesson"));freeEntry.put("scopeMatch","all");
        var service=new ProtectedContentService(policy(List.of(publicEntry,freeEntry)),accounts);
        assertThat(service.read("/content/public.json",null).bytes()).isNotEmpty();
        denied(()->service.read("/content/free.json",null),401,"AUTHENTICATION_REQUIRED");
        when(accounts.isEnabled(learner.accountId())).thenReturn(true);
        assertThat(service.read("/content/free.json",learner).bytes()).isNotEmpty();
        verify(accounts,never()).findTopicGrants(any());
    }
    @Test void invalidScopeMatchingValuesAndTypesFailClosed() throws Exception {
        for(Object value:Arrays.asList("ALL","", "some",true,1,List.of("all"),Map.of("rule","all"),null)) {
            var entry=asset("/content/bundle.json","pro",List.of("bundle-lesson"));entry.put("scopeMatch",value);
            assertThatThrownBy(()->policy(List.of(entry))).isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Invalid scope matching rule");
        }
    }
    @Test void everyStudyPlanNamespaceAssetRejectsCurriculumGrantsRegardlessOfTier() throws Exception {
        for(String path:List.of("/content/study-plans/index.json","/content/study-plans/templates/sample-template.json","/content/study-plans/other.json")) {
            for(String tier:List.of("public","free","pro")) {
                var entry=asset(path,tier,List.of("would-unlock-curriculum"));entry.put("scopeMatch","all");
                assertThatThrownBy(()->policy(List.of(entry))).isInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage("Study plan assets cannot grant curriculum access");
            }
        }
    }
    @Test void validStudyPlanIndexAndTemplateAssetsCannotAddContentGrants() throws Exception {
        var index=asset("/content/study-plans/index.json","free",List.of());
        var template=asset("/content/study-plans/templates/sample-template.json","pro",List.of());template.put("scopeMatch","all");
        var policy=policy(List.of(index,template));
        assertThat(policy.freeContentIds()).isEmpty();
        assertThat(policy.contentGrants(Set.of())).isEmpty();
        assertThat(policy.contentGrants(Set.copyOf(BUNDLE_SCOPES))).isEmpty();
    }
    @Test void allScopeProAssetsStillRequireNonemptyScopes() throws Exception {
        var entry=asset("/content/bundle.json","pro",List.of("bundle-lesson"));
        entry.put("scopeMatch","all");entry.put("scopes",List.of());
        assertThatThrownBy(()->policy(List.of(entry))).isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Invalid course scope");
    }
}
