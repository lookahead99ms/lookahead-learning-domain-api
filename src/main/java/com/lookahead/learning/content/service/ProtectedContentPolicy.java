package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable publication allowlist. Source manifests and bytes are server-owned. */
@Component
@Profile("accounts")
public class ProtectedContentPolicy {
    public record Asset(String path,String sha256,String mediaType,String tier,Set<String> scopes,Set<String> contentIds,String scopeMatch) {
        public boolean matchesScopes(Set<String> courseGrants) {
            return scopeMatch.equals("all") ? courseGrants.containsAll(scopes) : scopes.stream().anyMatch(courseGrants::contains);
        }
    }
    private final Map<String,Asset> assets;
    private final Path root;
    private final String version;
    private final Set<String> freeContentIds;
    public ProtectedContentPolicy(ObjectMapper mapper,@Value("${app.content.publication-path:}") String manifestPath,
            @Value("${app.content.root:}") String contentRoot) {
        if(manifestPath.isBlank() && contentRoot.isBlank()){assets=Map.of();root=null;version="disabled";freeContentIds=Set.of();return;}
        try {
            if(manifestPath.isBlank() || contentRoot.isBlank())throw new IllegalArgumentException("Both publication and content root are required");
            root=Path.of(contentRoot).toRealPath();Path manifest=Path.of(manifestPath);
            if(Files.size(manifest)>32*1024*1024)throw new IllegalArgumentException("Publication manifest too large");
            JsonNode data=mapper.readTree(Files.readString(manifest));
            if(!data.path("schemaVersion").asText().equals("content-publication/v1") || !data.path("assets").isArray() || data.path("assets").size()>50000)throw new IllegalArgumentException("Invalid publication schema");
            version=data.path("version").asText();if(!version.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid publication version");
            Map<String,Asset> entries=new HashMap<>();Set<String> free=new HashSet<>();
            for(var item:data.path("assets")) {
                String path=item.path("path").asText(),sha=item.path("sha256").asText(),type=item.path("mediaType").asText(),tier=item.path("tier").asText();
                if(!validPath(path) || !sha.matches("[a-f0-9]{64}") || !Set.of("public","free","pro").contains(tier) || !Set.of("application/json","text/html","text/css","text/javascript","image/svg+xml","image/png","image/jpeg","image/webp","font/woff2").contains(type))throw new IllegalArgumentException("Invalid asset entry");
                Set<String> scopes=strings(item.path("scopes")),ids=strings(item.path("contentIds"));
                String scopeMatch="any";
                if(item.has("scopeMatch")) {
                    if(!item.path("scopeMatch").isString() || !Set.of("any","all").contains(item.path("scopeMatch").asText()))throw new IllegalArgumentException("Invalid scope matching rule");
                    scopeMatch=item.path("scopeMatch").asText();
                }
                if(scopes.stream().anyMatch(scope->!scope.matches("(?:learn|grow|look-ahead):[a-z0-9-]+")) || (tier.equals("pro") && scopes.isEmpty()))throw new IllegalArgumentException("Invalid course scope");
                if(path.startsWith("/content/study-plans/") && !ids.isEmpty())throw new IllegalArgumentException("Study plan assets cannot grant curriculum access");
                if(entries.putIfAbsent(path,new Asset(path,sha,type,tier,scopes,ids,scopeMatch))!=null)throw new IllegalArgumentException("Duplicate asset path");
                if(!tier.equals("pro"))free.addAll(ids);
            }
            assets=Map.copyOf(entries);freeContentIds=Set.copyOf(free);
        } catch(Exception error){throw new IllegalStateException("Cannot load protected content publication",error);}
    }
    private static Set<String> strings(JsonNode node) {
        if(!node.isArray() || node.size()>50000)throw new IllegalArgumentException("Expected bounded publication array");
        Set<String> result=new HashSet<>();for(var value:node){if(!value.isString() || value.asText().isBlank() || value.asText().length()>300 || !result.add(value.asText()))throw new IllegalArgumentException("Invalid publication identity");}return Set.copyOf(result);
    }
    public static boolean validPath(String path){return path.length()<=600 && path.matches("/content/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:json|html|css|js|svg|png|jpg|jpeg|webp|woff2)");}
    public String version(){return version;}
    public Set<String> freeContentIds(){return freeContentIds;}
    public Set<String> contentGrants(Set<String> courseGrants) {
        var result=new HashSet<>(freeContentIds);
        for(var asset:assets.values()) if(asset.matchesScopes(courseGrants)) result.addAll(asset.contentIds());
        return Set.copyOf(result);
    }
    public Optional<Asset> find(String path){return Optional.ofNullable(assets.get(path));}
    public byte[] read(Asset asset) {
        try {
            Path file=root.resolve(asset.path().substring("/content/".length())).normalize();
            for(Path current=file;current!=null && current.startsWith(root);current=current.getParent())if(Files.isSymbolicLink(current))throw new IllegalStateException("Symbolic asset path");
            if(!file.toRealPath().startsWith(root) || Files.size(file)>32*1024*1024)throw new IllegalStateException("Invalid asset boundary");
            byte[] bytes=Files.readAllBytes(file);String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!actual.equals(asset.sha256()))throw new IllegalStateException("Changed publication bytes");return bytes;
        }catch(Exception failure){throw new AccountFailure(503,"CONTENT_UNAVAILABLE","This content is temporarily unavailable. Retry shortly.");}
    }
}
