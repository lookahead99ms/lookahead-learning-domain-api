package com.lookahead.domain.tls;

import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.ssl.SslBundles;
import static org.junit.jupiter.api.Assertions.*;

class InjectedCredentialsTest {
    @TempDir Path directory;
    String originalTrust;
    void command(String... arguments) throws Exception {
        var command = new ArrayList<String>(); command.add("openssl"); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
    }
    String text(String name) throws Exception { return Files.readString(directory.resolve(name)); }
    void leaf(String name, String subject, String san, String purpose, String days, String key) throws Exception {
        command("req", "-new", "-key", key, "-out", name+".csr", "-subj", subject);
        Files.writeString(directory.resolve("extensions"), "basicConstraints=critical,CA:FALSE\nsubjectAltName=DNS:"+san+"\nextendedKeyUsage="+purpose+"\n");
        var arguments=new ArrayList<>(List.of("x509", "-req", "-in", name+".csr", "-CA", "ca.pem", "-CAkey", "ca.key", "-CAcreateserial",
            "-out", name+".pem", "-extfile", "extensions"));
        if (days.equals("expired") || days.equals("future")) {
            // `ca` explicit validity dates work on CI's OpenSSL 3.0 as well as newer versions.
            Files.writeString(directory.resolve("index"), "");
            Files.writeString(directory.resolve("serial"), "01\n");
            Files.writeString(directory.resolve("ca.conf"), """
                [ca]
                default_ca=fixture
                [fixture]
                database=index
                serial=serial
                new_certs_dir=.
                certificate=ca.pem
                private_key=ca.key
                default_md=sha256
                policy=subject
                [subject]
                commonName=supplied
                """);
            arguments = new ArrayList<>(List.of("ca", "-batch", "-notext", "-config", "ca.conf",
                "-in", name+".csr", "-out", name+".pem", "-extfile", "extensions", "-startdate",
                days.equals("expired") ? "20000101000000Z" : "20990101000000Z", "-enddate",
                days.equals("expired") ? "20010101000000Z" : "20990103000000Z"));
        } else arguments.addAll(List.of("-days",days));
        command(arguments.toArray(String[]::new));
    }
    @BeforeEach void fixtures() throws Exception {
        command("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.pem", "-days", "5", "-subj", "/CN=Synthetic root", "-addext", "basicConstraints=critical,CA:TRUE");
        command("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", "server.key");
        command("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", "client.key");
        leaf("server", "/CN=dev.example.com", "dev.example.com", "serverAuth", "3", "server.key");
        leaf("client", "/CN=gateway.lookahead-dev.internal", "gateway.lookahead-dev.internal", "clientAuth", "3", "client.key");
        originalTrust=System.getProperty("javax.net.ssl.trustStore");
        KeyStore store=KeyStore.getInstance("JKS"); store.load(null, null);
        store.setCertificateEntry("synthetic", InjectedCredentialValidator.certificates(text("ca.pem")).getFirst());
        try (var output=Files.newOutputStream(directory.resolve("trust.jks"))) { store.store(output, "changeit".toCharArray()); }
        System.setProperty("javax.net.ssl.trustStore", directory.resolve("trust.jks").toString());
    }
    @AfterEach void restoreTrust() {
        if(originalTrust==null)System.clearProperty("javax.net.ssl.trustStore");else System.setProperty("javax.net.ssl.trustStore",originalTrust);
    }
    Map<String,String> input() throws Exception {
        return new LinkedHashMap<>(Map.ofEntries(
            Map.entry("LOOKAHEAD_TLS_SCHEMA","lookahead-tls/v1"), Map.entry("LOOKAHEAD_TLS_SERVER_NAME","dev.example.com"),
            Map.entry("LOOKAHEAD_TLS_SERVER_CERTIFICATE",text("server.pem")),Map.entry("LOOKAHEAD_TLS_SERVER_KEY",text("server.key")),
            Map.entry("LOOKAHEAD_TLS_TRUST",text("ca.pem")),Map.entry("LOOKAHEAD_TLS_CLIENT_CERTIFICATE",text("client.pem")),Map.entry("LOOKAHEAD_TLS_CLIENT_KEY",text("client.key")),
            Map.entry("LOOKAHEAD_DOMAIN_GATEWAY_SECRET","synthetic-gateway-secret"),Map.entry("APP_OAUTH_CLIENT_ID","SyntheticClient1"),
            Map.entry("LOOKAHEAD_GATEWAY_CLIENT_SECRET","synthetic-oauth-secret"),Map.entry("LOOKAHEAD_ADMISSION_CLIENT_ID","SyntheticClient2"),
            Map.entry("LOOKAHEAD_ADMISSION_CLIENT_SECRET","synthetic-admission-secret"),Map.entry("LOOKAHEAD_DOMAIN_DB_PASSWORD","a".repeat(48))));
    }
    @Test void positiveBundlesAreConsumedBySpringWithoutFiles() throws Exception {
        for(String service:List.of("gateway","domain")) {
            var properties=InjectedCredentialValidator.properties(input(), service);
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class))
                .withInitializer(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic",properties)))
                .run(c->{ assertNull(c.getStartupFailure()); var bundles=c.getBean(SslBundles.class);
                    assertNotNull(bundles.getBundle("serving").createSslContext());
                    if(service.equals("gateway")) assertNotNull(bundles.getBundle("internal").createSslContext()); });
        }
    }
    @Test void initializerRunsOnlyOnCloudProfileAndOverridesUnvalidatedConfiguration() throws Exception {
        try(var context=new GenericApplicationContext()) {
            var initializer=new InjectedCredentials();initializer.initialize(context);
            assertFalse(context.getEnvironment().getPropertySources().contains("validatedInjectedCredentials"));
            context.getEnvironment().setActiveProfiles("lower-cost-dev");
            var values=new HashMap<String,Object>(input());values.put("spring.ssl.bundle.pem.serving.keystore.private-key","invalid override");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic",values));
            initializer.initialize(context);
            assertEquals(text("server.key"),context.getEnvironment().getProperty("spring.ssl.bundle.pem.serving.keystore.private-key"));
        }
        assertTrue(new String(getClass().getResourceAsStream("/META-INF/spring.factories").readAllBytes()).contains(InjectedCredentials.class.getName()));
    }
    @Test void unresolvedEnvironmentPlaceholdersDoNotLeakFromStartup() {
        try(var context=new GenericApplicationContext()) {
            context.getEnvironment().setActiveProfiles("lower-cost-dev");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic",Map.of("LOOKAHEAD_TLS_SCHEMA","${private-marker}")));
            var failure=assertThrows(IllegalStateException.class,()->new InjectedCredentials().initialize(context));
            assertEquals("Injected credential validation failed; application startup denied",failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void missingInputsRejectBeforeContextRefreshWithoutLeakingValues() throws Exception {
        for(String service:List.of("gateway","domain")) {
            var keys=new ArrayList<>(input().keySet());
            if(service.equals("domain"))keys.removeAll(List.of("LOOKAHEAD_TLS_CLIENT_CERTIFICATE","LOOKAHEAD_TLS_CLIENT_KEY","APP_OAUTH_CLIENT_ID","LOOKAHEAD_GATEWAY_CLIENT_SECRET","LOOKAHEAD_ADMISSION_CLIENT_ID","LOOKAHEAD_ADMISSION_CLIENT_SECRET"));
            else keys.remove("LOOKAHEAD_DOMAIN_DB_PASSWORD");
            for(String key:keys) {
                var values=input();values.remove(key);
                var failure=assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,service),key);
                assertEquals("Injected credential validation failed; application startup denied",failure.getMessage());assertNull(failure.getCause());
            }
        }
    }
    @Test void stableServerNameIsAcceptedOnlyWhenTheLeafListsIt() throws Exception {
        // DLV-968 decision V 2026-11-01: the task definition injects the stable name domain.lookahead-dev.internal; the slot names stay
        // accepted until the slot stacks are gone. The validator has no allow-list: it accepts exactly the injected name when the leaf
        // lists it, refuses an unlisted name, and refuses the stable name while the leaf lists only the slot names.
        var stable="domain.lookahead-dev.internal"; var slots=List.of("domain-blue.lookahead-dev.internal","domain-green.lookahead-dev.internal");
        leaf("renewed", "/CN="+stable, stable+",DNS:"+String.join(",DNS:",slots), "serverAuth", "3", "server.key");
        for(String name:List.of(stable,slots.get(0),slots.get(1))) {
            var values=input();values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE",text("renewed.pem"));values.put("LOOKAHEAD_TLS_SERVER_NAME",name);
            assertEquals(text("server.key"),InjectedCredentialValidator.properties(values,"domain").get("spring.ssl.bundle.pem.serving.keystore.private-key"),name);
        }
        var unlisted=input();unlisted.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE",text("renewed.pem"));unlisted.put("LOOKAHEAD_TLS_SERVER_NAME","domain-red.lookahead-dev.internal");
        assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(unlisted,"domain"));
        leaf("slotsonly", "/CN="+slots.get(0), String.join(",DNS:",slots), "serverAuth", "3", "server.key");
        var old=input();old.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE",text("slotsonly.pem"));old.put("LOOKAHEAD_TLS_SERVER_NAME",stable);
        var failure=assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(old,"domain"));
        assertEquals("Injected credential validation failed; application startup denied",failure.getMessage());assertNull(failure.getCause());
    }
    @Test void wrongHostnamePurposeKeyAndTrustReject() throws Exception {
        for(var entry:Map.of("LOOKAHEAD_TLS_SERVER_NAME","wrong.example.com","LOOKAHEAD_TLS_SERVER_KEY",text("ca.key"),"LOOKAHEAD_TLS_TRUST",text("server.pem"),"LOOKAHEAD_TLS_SERVER_CERTIFICATE",text("client.pem"),"LOOKAHEAD_TLS_SCHEMA","wrong").entrySet()) {
            var values=input();values.put(entry.getKey(),entry.getValue());
            assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"domain"));
        }
        command("req","-x509","-newkey","rsa:2048","-nodes","-keyout","other.key","-out","other.pem","-days","5","-subj","/CN=Untrusted root","-addext","basicConstraints=critical,CA:TRUE");
        var values=input();values.put("LOOKAHEAD_TLS_TRUST",text("other.pem"));
        assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"domain"));
    }
    @Test void shortLifetimeExpiredAndWrongClientSubjectReject() throws Exception {
        for(String days:List.of("1","expired","future")) {
            leaf("short", "/CN=dev.example.com", "dev.example.com", "serverAuth", days, "server.key");
            var values=input();values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE",text("short.pem"));
            assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"domain"));
        }
        leaf("extra", "/O=Unexpected/CN=gateway.lookahead-dev.internal", "gateway.lookahead-dev.internal", "clientAuth", "3", "client.key");
        var values=input();values.put("LOOKAHEAD_TLS_CLIENT_CERTIFICATE",text("extra.pem"));
        assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"gateway"));
    }
    @Test void wrongExtendedUsageAndUnsupportedKeyFormatRejectIndependently() throws Exception {
        leaf("purpose", "/CN=dev.example.com", "dev.example.com", "clientAuth", "3", "server.key");
        var values=input();values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE",text("purpose.pem"));
        assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"domain"));
        var badKey=input();badKey.put("LOOKAHEAD_TLS_SERVER_KEY","-----BEGIN RSA PRIVATE KEY-----\ninvalid");
        assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(badKey,"domain"));
    }

    @Test void privateKeyEnvelopeMustBeCompleteAndContainOnlyOnePkcs8Key() throws Exception {
        String key = text("server.key");
        for (String invalid : List.of(
                key.replaceAll("-{5}END PRIVATE KEY-{5}", ""),
                key.replace("END PRIVATE", "END RSA PRIVATE"),
                key.replace("BEGIN PRIVATE", "BEGIN ENCRYPTED PRIVATE"),
                key.replaceFirst("\\R", ""), key + key, key + "trailing-source", "prefix" + key)) {
            var values = input(); values.put("LOOKAHEAD_TLS_SERVER_KEY", invalid);
            var failure = assertThrows(IllegalStateException.class,
                    () -> InjectedCredentialValidator.properties(values, "domain"));
            assertEquals("Injected credential validation failed; application startup denied", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void duplicateGatewayKeysAndCaLeafReject() throws Exception {
        leaf("same", "/CN=gateway.lookahead-dev.internal", "gateway.lookahead-dev.internal", "clientAuth", "3", "server.key");
        var values=input();values.put("LOOKAHEAD_TLS_CLIENT_CERTIFICATE",text("same.pem"));values.put("LOOKAHEAD_TLS_CLIENT_KEY",text("server.key"));
        assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"gateway"));
        values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE",text("ca.pem"));values.put("LOOKAHEAD_TLS_SERVER_KEY",text("ca.key"));
        assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"gateway"));
    }
    @Test void scalarAndMalformedPemBoundaries() throws Exception {
        for(String scalar:List.of("short","a".repeat(4097),"synthetic-secret\nvalue")) {
            var values=input();values.put("LOOKAHEAD_DOMAIN_GATEWAY_SECRET",scalar);
            assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"domain"));
        }
        for(String pem:List.of("", "file:/tmp/forbidden", "x".repeat(65537), text("server.pem").repeat(9))) {
            var values=input();values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE",pem);
            assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"domain"));
        }
        for(String id:List.of("invalid-id","a".repeat(129)))assertThrows(IllegalArgumentException.class,()->InjectedCredentialValidator.clientId(id));
        var values=input();values.put("LOOKAHEAD_DOMAIN_DB_PASSWORD","wrongshape-but-long-enough");
        assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"domain"));
        assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"web"));
    }

    /** DLV-969 (owner, 2026-11-04): a refused injected credential is named by its variable in the log, never by its value. */
    @Test void aRefusedCredentialIsNamedInTheLogWithoutItsValue() throws Exception {
        var cases=new LinkedHashMap<String[],String>();
        cases.put(new String[]{"LOOKAHEAD_TLS_SCHEMA","lookahead-tls/v0"},"LOOKAHEAD_TLS_SCHEMA is missing or is not lookahead-tls/v1");
        cases.put(new String[]{"LOOKAHEAD_TLS_SERVER_NAME","wrong.example.com"},"LOOKAHEAD_TLS_SERVER_CERTIFICATE does not list LOOKAHEAD_TLS_SERVER_NAME among its DNS names");
        cases.put(new String[]{"LOOKAHEAD_TLS_SERVER_KEY",text("ca.key")},"LOOKAHEAD_TLS_SERVER_KEY does not belong to LOOKAHEAD_TLS_SERVER_CERTIFICATE");
        cases.put(new String[]{"LOOKAHEAD_TLS_SERVER_KEY",null},"LOOKAHEAD_TLS_SERVER_KEY is missing or is not exactly one complete unencrypted PKCS#8 PEM private key");
        cases.put(new String[]{"LOOKAHEAD_TLS_TRUST",text("server.pem")},"LOOKAHEAD_TLS_TRUST is missing, or is not 1 to 8 valid self-signed CA certificates in PEM");
        cases.put(new String[]{"LOOKAHEAD_TLS_SERVER_CERTIFICATE","not a certificate"},"LOOKAHEAD_TLS_SERVER_CERTIFICATE is missing, is not 1 to 8 PEM certificates");
        cases.put(new String[]{"LOOKAHEAD_DOMAIN_GATEWAY_SECRET","short"},"LOOKAHEAD_DOMAIN_GATEWAY_SECRET is missing, shorter than 16");
        cases.put(new String[]{"LOOKAHEAD_DOMAIN_DB_PASSWORD","synthetic-database-password-of-wrong-shape"},"LOOKAHEAD_DOMAIN_DB_PASSWORD is missing or is not exactly 48 letters and digits");
        for(var entry:cases.entrySet()) {
            var values=input();values.put(entry.getKey()[0],entry.getKey()[1]);
            try(var log=com.lookahead.domain.LogCapture.start("lookahead.startup")) {
                var failure=assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"domain"));
                assertEquals(InjectedCredentialValidator.DENIED,failure.getMessage());assertNull(failure.getCause());
                var line=log.only("startup.check");
                assertEquals(ch.qos.logback.classic.Level.WARN,line.getLevel());
                assertTrue(line.getFormattedMessage().startsWith("Startup refused: "+entry.getValue()),line.getFormattedMessage());
                assertEquals("INJECTED_CREDENTIAL_INVALID",com.lookahead.domain.LogCapture.keys(line).get("reason"));
                log.containsNone("wrong.example.com","dev.example.com","-----BEGIN","synthetic-gateway-secret","synthetic-database-password","lookahead-tls/v0");
            }
        }
        var values=input();values.put("LOOKAHEAD_TLS_CLIENT_KEY",text("server.key"));
        try(var log=com.lookahead.domain.LogCapture.start("lookahead.startup")) {
            assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(values,"gateway"));
            assertTrue(log.only("startup.check").getFormattedMessage().startsWith("Startup refused: LOOKAHEAD_TLS_CLIENT_KEY does not belong to LOOKAHEAD_TLS_CLIENT_CERTIFICATE"));
        }
        try(var log=com.lookahead.domain.LogCapture.start("lookahead.startup")) {
            assertThrows(IllegalStateException.class,()->InjectedCredentialValidator.properties(input(),"web"));
            assertTrue(log.only("startup.check").getFormattedMessage().startsWith("Startup refused: service is not gateway or domain"));
        }
    }
    @Test void anUnresolvedInjectedValueIsNamedBeforeStartupStops() {
        try(var context=new GenericApplicationContext(); var log=com.lookahead.domain.LogCapture.start("lookahead.startup")) {
            context.getEnvironment().setActiveProfiles("lower-cost-dev");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic",Map.of("LOOKAHEAD_TLS_TRUST","${private-marker}")));
            assertThrows(IllegalStateException.class,()->new InjectedCredentials().initialize(context));
            var line=log.only("startup.check");
            assertTrue(line.getFormattedMessage().startsWith("Startup refused: LOOKAHEAD_TLS_TRUST could not be read: it holds a ${...} placeholder with no value"),line.getFormattedMessage());
            assertEquals("INJECTED_CREDENTIAL_UNRESOLVED",com.lookahead.domain.LogCapture.keys(line).get("reason"));
            log.containsNone("private-marker");
        }
    }
}
