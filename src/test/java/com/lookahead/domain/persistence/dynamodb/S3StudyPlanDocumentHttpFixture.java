package com.lookahead.domain.persistence.dynamodb;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Finite SDK protocol fixture, not an S3/IAM emulator. No external endpoint or default credentials. */
final class S3StudyPlanDocumentHttpFixture implements AutoCloseable {
    record Blob(byte[] bytes, Map<String,String> metadata, String version, String etag, String kms) {}
    record Request(String method, String path, String query, Map<String,List<String>> headers) {}
    static final S3StudyPlanDocumentObjects.Settings SETTINGS = new S3StudyPlanDocumentObjects.Settings("prod",
            "lookahead-prod-user-study-plan-details-fixture","123456789012",
            "arn:aws:kms:us-east-2:123456789012:key/12345678-1234-1234-1234-123456789012");
    final Map<String,Blob> latest = new ConcurrentHashMap<>();
    final Map<String,Blob> versions = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger putCalls = new AtomicInteger();
    final S3Client client;
    private final HttpServer server;
    volatile boolean loseNextPutResponse;
    volatile boolean failPut;
    volatile boolean failGet;
    volatile Runnable afterPut = () -> {};

    S3StudyPlanDocumentHttpFixture() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            try {
                String path=exchange.getRequestURI().getPath();
                String method=exchange.getRequestMethod();
                requests.add(new Request(method,path,exchange.getRequestURI().getRawQuery(),Map.copyOf(exchange.getRequestHeaders())));
                if (method.equals("PUT")) {
                    putCalls.incrementAndGet();
                    byte[] bytes=exchange.getRequestBody().readAllBytes();
                    if(failPut){error(exchange,503,"SlowDown");return;}
                    if(!"*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))){error(exchange,400,"MissingConditionalWrite");return;}
                    var metadata=new java.util.HashMap<String,String>();
                    exchange.getRequestHeaders().forEach((k,v)->{if(k.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-meta-"))metadata.put(k.substring(11).toLowerCase(java.util.Locale.ROOT),v.getFirst());});
                    var blob=new Blob(bytes,Map.copyOf(metadata),"fixture/version+"+putCalls.get(),"\"opaque-etag-"+putCalls.get()+"\"",exchange.getRequestHeaders().getFirst("x-amz-server-side-encryption-aws-kms-key-id"));
                    if(latest.putIfAbsent(path,blob)!=null){error(exchange,412,"PreconditionFailed");return;}
                    versions.put(path+"?"+blob.version(),blob);
                    afterPut.run();
                    if(loseNextPutResponse){loseNextPutResponse=false;error(exchange,503,"SlowDown");return;}
                    exchange.getResponseHeaders().set("ETag",blob.etag());
                    exchange.getResponseHeaders().set("x-amz-version-id",blob.version());
                    exchange.sendResponseHeaders(200,-1);
                } else if(method.equals("GET")) {
                    if(failGet){error(exchange,503,"SlowDown");return;}
                    String version=null;
                    String query=exchange.getRequestURI().getRawQuery();
                    if(query!=null)for(String part:query.split("&"))if(part.startsWith("versionId="))version=URLDecoder.decode(part.substring(10),StandardCharsets.UTF_8);
                    var blob=version==null?latest.get(path):versions.get(path+"?"+version);
                    if(blob==null){error(exchange,404,"NoSuchKey");return;}
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.getResponseHeaders().set("ETag",blob.etag());
                    exchange.getResponseHeaders().set("x-amz-version-id",blob.version());
                    exchange.getResponseHeaders().set("x-amz-server-side-encryption","aws:kms");
                    exchange.getResponseHeaders().set("x-amz-server-side-encryption-aws-kms-key-id",blob.kms());
                    blob.metadata().forEach((k,v)->exchange.getResponseHeaders().set("x-amz-meta-"+k,v));
                    exchange.sendResponseHeaders(200,blob.bytes().length);
                    exchange.getResponseBody().write(blob.bytes());
                } else error(exchange,405,"MethodNotAllowed");
            } finally {exchange.close();}
        });
        server.start();
        client=S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:"+server.getAddress().getPort()))
                .region(Region.US_EAST_2).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic","synthetic")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .overrideConfiguration(c->c.retryPolicy(RetryPolicy.none()).apiCallTimeout(Duration.ofSeconds(10)))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
    S3StudyPlanDocumentObjects objects(){return new S3StudyPlanDocumentObjects(client,SETTINGS);}
    String path(S3StudyPlanDocumentObjects.Id id){return "/"+SETTINGS.bucket()+"/"+id.key();}
    void corrupt(S3StudyPlanDocumentObjects.Reference ref, Blob blob){versions.put(path(ref.descriptor().id())+"?"+ref.versionId(),blob);}
    private static void error(com.sun.net.httpserver.HttpExchange exchange,int status,String code)throws java.io.IOException {
        byte[] body=("<Error><Code>"+code+"</Code><Message>Synthetic fixture response</Message></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/xml");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
    }
    @Override public void close(){client.close();server.stop(0);}
}
