package com.lookahead.domain.security.tls;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.log.LogCapture;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class InjectedCredentialInitializerTest {
  @TempDir Path directory;
  String originalTrust;

  void command(String... arguments) throws Exception {
    var command = new ArrayList<String>();
    command.add("openssl");
    command.addAll(List.of(arguments));
    var process =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertTrue(process.waitFor(20, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
  }

  String text(String name) throws Exception {
    return Files.readString(directory.resolve(name));
  }

  void leaf(String name, String subject, String san, String purpose, String days, String key)
      throws Exception {
    command("req", "-new", "-key", key, "-out", name + ".csr", "-subj", subject);
    Files.writeString(
        directory.resolve("extensions"),
        "basicConstraints=critical,CA:FALSE\nsubjectAltName=DNS:"
            + san
            + "\nextendedKeyUsage="
            + purpose
            + "\n");
    var arguments =
        new ArrayList<>(
            List.of(
                "x509",
                "-req",
                "-in",
                name + ".csr",
                "-CA",
                "ca.pem",
                "-CAkey",
                "ca.key",
                "-CAcreateserial",
                "-out",
                name + ".pem",
                "-extfile",
                "extensions"));
    if (days.equals("expired") || days.equals("future")) {
      // `ca` explicit validity dates work on CI's OpenSSL 3.0 as well as newer versions.
      Files.writeString(directory.resolve("index"), "");
      Files.writeString(directory.resolve("serial"), "01\n");
      Files.writeString(
          directory.resolve("ca.conf"),
          """
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
      arguments =
          new ArrayList<>(
              List.of(
                  "ca",
                  "-batch",
                  "-notext",
                  "-config",
                  "ca.conf",
                  "-in",
                  name + ".csr",
                  "-out",
                  name + ".pem",
                  "-extfile",
                  "extensions",
                  "-startdate",
                  days.equals("expired") ? "20000101000000Z" : "20990101000000Z",
                  "-enddate",
                  days.equals("expired") ? "20010101000000Z" : "20990103000000Z"));
    } else arguments.addAll(List.of("-days", days));
    command(arguments.toArray(String[]::new));
  }

  @BeforeEach
  void fixtures() throws Exception {
    command(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "ca.key",
        "-out",
        "ca.pem",
        "-days",
        "5",
        "-subj",
        "/CN=Synthetic root",
        "-addext",
        "basicConstraints=critical,CA:TRUE");
    command(
        "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", "server.key");
    command(
        "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", "client.key");
    leaf("server", "/CN=dev.example.com", "dev.example.com", "serverAuth", "3", "server.key");
    leaf(
        "client",
        "/CN=gateway.lookahead-dev.internal",
        "gateway.lookahead-dev.internal",
        "clientAuth",
        "3",
        "client.key");
    originalTrust = System.getProperty("javax.net.ssl.trustStore");
    KeyStore store = KeyStore.getInstance("JKS");
    store.load(null, null);
    store.setCertificateEntry(
        "synthetic", InjectedCredentialValidator.certificates(text("ca.pem")).getFirst());
    try (var output = Files.newOutputStream(directory.resolve("trust.jks"))) {
      store.store(output, "changeit".toCharArray());
    }
    System.setProperty("javax.net.ssl.trustStore", directory.resolve("trust.jks").toString());
  }

  @AfterEach
  void restoreTrust() {
    if (originalTrust == null) System.clearProperty("javax.net.ssl.trustStore");
    else System.setProperty("javax.net.ssl.trustStore", originalTrust);
  }

  Map<String, String> input() throws Exception {
    return new LinkedHashMap<>(
        Map.ofEntries(
            Map.entry("LOOKAHEAD_TLS_SCHEMA", "lookahead-tls/v1"),
                Map.entry("LOOKAHEAD_TLS_SERVER_NAME", "dev.example.com"),
            Map.entry("LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("server.pem")),
                Map.entry("LOOKAHEAD_TLS_SERVER_KEY", text("server.key")),
            Map.entry("LOOKAHEAD_TLS_TRUST", text("ca.pem")),
                Map.entry("LOOKAHEAD_DOMAIN_GATEWAY_SECRET", "synthetic-gateway-secret")));
  }

  /**
   * Runs the check on {@code values}, expects the refusal with its fixed text and returns its one
   * startup line.
   */
  String refused(Map<String, String> values) {
    try (var log = LogCapture.start("lookahead.startup")) {
      var failure =
          assertThrows(
              IllegalStateException.class, () -> InjectedCredentialValidator.properties(values));
      assertEquals(InjectedCredentialValidator.DENIED, failure.getMessage());
      assertNull(failure.getCause());
      var line = log.only("startup.check");
      assertEquals(Level.ERROR, line.getLevel());
      assertEquals("INJECTED_CREDENTIAL_INVALID", LogCapture.keys(line).get("reason"));
      return line.getFormattedMessage();
    }
  }

  @Test
  void positiveBundlesAreConsumedBySpringWithoutFiles() throws Exception {
    var properties = InjectedCredentialValidator.properties(input());
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class))
        .withInitializer(c ->
            c.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("synthetic", properties)))
        .run(c -> {
          assertNull(c.getStartupFailure());
          var bundles = c.getBean(SslBundles.class);
          assertNotNull(bundles.getBundle("serving").createSslContext());
        });
  }

  @Test
  void initializerRunsOnlyOnCloudProfileAndOverridesUnvalidatedConfiguration() throws Exception {
    try (var context = new GenericApplicationContext()) {
      var initializer = new InjectedCredentialInitializer();
      initializer.initialize(context);
      assertFalse(
          context.getEnvironment().getPropertySources().contains("validatedInjectedCredentials"));
      context.getEnvironment().setActiveProfiles("lower-cost-dev");
      var values = new HashMap<String, Object>(input());
      values.put("spring.ssl.bundle.pem.serving.keystore.private-key", "invalid override");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("synthetic", values));
      initializer.initialize(context);
      assertEquals(
          text("server.key"),
          context
              .getEnvironment()
              .getProperty("spring.ssl.bundle.pem.serving.keystore.private-key"));
    }
    assertTrue(
        new String(getClass().getResourceAsStream("/META-INF/spring.factories").readAllBytes())
            .contains(InjectedCredentialInitializer.class.getName()));
  }

  @Test
  void unresolvedEnvironmentPlaceholdersDoNotLeakFromStartup() {
    try (var context = new GenericApplicationContext()) {
      context.getEnvironment().setActiveProfiles("lower-cost-dev");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "synthetic", Map.of("LOOKAHEAD_TLS_SCHEMA", "${private-marker}")));
      var failure =
          assertThrows(
              IllegalStateException.class,
              () -> new InjectedCredentialInitializer().initialize(context));
      assertEquals(
          "Injected credential validation failed; application startup denied",
          failure.getMessage());
      assertNull(failure.getCause());
    }
  }

  @Test
  void missingInputsRejectBeforeContextRefreshWithoutLeakingValues() throws Exception {
    var keys = new ArrayList<>(input().keySet());
    assertEquals(
        Set.of(
            "LOOKAHEAD_TLS_SCHEMA",
            "LOOKAHEAD_TLS_SERVER_NAME",
            "LOOKAHEAD_TLS_SERVER_CERTIFICATE",
            "LOOKAHEAD_TLS_SERVER_KEY",
            "LOOKAHEAD_TLS_TRUST",
            "LOOKAHEAD_DOMAIN_GATEWAY_SECRET"),
        new HashSet<>(keys));
    for (String key : keys) {
      var values = input();
      values.remove(key);
      var failure =
          assertThrows(
              IllegalStateException.class,
              () -> InjectedCredentialValidator.properties(values),
              key);
      assertEquals(
          "Injected credential validation failed; application startup denied",
          failure.getMessage());
      assertNull(failure.getCause());
    }
  }

  @Test
  void stableServerNameIsAcceptedOnlyWhenTheLeafListsIt() throws Exception {
    // DLV-968 decision V 2026-11-01: the task definition injects the stable name
    // domain.lookahead-dev.internal; the slot names stay
    // accepted until the slot stacks are gone. The validator has no allow-list: it accepts exactly
    // the injected name when the leaf
    // lists it, refuses an unlisted name, and refuses the stable name while the leaf lists only the
    // slot names.
    var stable = "domain.lookahead-dev.internal";
    var slots =
        List.of("domain-blue.lookahead-dev.internal", "domain-green.lookahead-dev.internal");
    leaf(
        "renewed",
        "/CN=" + stable,
        stable + ",DNS:" + String.join(",DNS:", slots),
        "serverAuth",
        "3",
        "server.key");
    for (String name : List.of(stable, slots.get(0), slots.get(1))) {
      var values = input();
      values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("renewed.pem"));
      values.put("LOOKAHEAD_TLS_SERVER_NAME", name);
      assertEquals(
          text("server.key"),
          InjectedCredentialValidator.properties(values)
              .get("spring.ssl.bundle.pem.serving.keystore.private-key"),
          name);
    }
    var unlisted = input();
    unlisted.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("renewed.pem"));
    unlisted.put("LOOKAHEAD_TLS_SERVER_NAME", "domain-red.lookahead-dev.internal");
    assertThrows(
        IllegalStateException.class, () -> InjectedCredentialValidator.properties(unlisted));
    leaf(
        "slotsonly",
        "/CN=" + slots.get(0),
        String.join(",DNS:", slots),
        "serverAuth",
        "3",
        "server.key");
    var old = input();
    old.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("slotsonly.pem"));
    old.put("LOOKAHEAD_TLS_SERVER_NAME", stable);
    var failure =
        assertThrows(
            IllegalStateException.class, () -> InjectedCredentialValidator.properties(old));
    assertEquals(
        "Injected credential validation failed; application startup denied", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void wrongHostnamePurposeKeyAndTrustReject() throws Exception {
    for (var entry :
        Map.of(
                "LOOKAHEAD_TLS_SERVER_NAME",
                "wrong.example.com",
                "LOOKAHEAD_TLS_SERVER_KEY",
                text("ca.key"),
                "LOOKAHEAD_TLS_TRUST",
                text("server.pem"),
                "LOOKAHEAD_TLS_SERVER_CERTIFICATE",
                text("client.pem"),
                "LOOKAHEAD_TLS_SCHEMA",
                "wrong")
            .entrySet()) {
      var values = input();
      values.put(entry.getKey(), entry.getValue());
      assertThrows(
          IllegalStateException.class, () -> InjectedCredentialValidator.properties(values));
    }
    command(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "other.key",
        "-out",
        "other.pem",
        "-days",
        "5",
        "-subj",
        "/CN=Untrusted root",
        "-addext",
        "basicConstraints=critical,CA:TRUE");
    var values = input();
    values.put("LOOKAHEAD_TLS_TRUST", text("other.pem"));
    assertThrows(IllegalStateException.class, () -> InjectedCredentialValidator.properties(values));
  }

  @Test
  void shortLifetimeExpiredAndNotYetValidServerCertificatesReject() throws Exception {
    for (String days : List.of("1", "expired", "future")) {
      leaf("short", "/CN=dev.example.com", "dev.example.com", "serverAuth", days, "server.key");
      var values = input();
      values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("short.pem"));
      assertThrows(
          IllegalStateException.class, () -> InjectedCredentialValidator.properties(values));
    }
  }

  @Test
  void wrongExtendedUsageAndUnsupportedKeyFormatRejectIndependently() throws Exception {
    leaf("purpose", "/CN=dev.example.com", "dev.example.com", "clientAuth", "3", "server.key");
    var values = input();
    values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("purpose.pem"));
    assertThrows(IllegalStateException.class, () -> InjectedCredentialValidator.properties(values));
    var badKey = input();
    badKey.put("LOOKAHEAD_TLS_SERVER_KEY", "-----BEGIN RSA PRIVATE KEY-----\ninvalid");
    assertThrows(IllegalStateException.class, () -> InjectedCredentialValidator.properties(badKey));
  }

  @Test
  void privateKeyEnvelopeMustBeCompleteAndContainOnlyOnePkcs8Key() throws Exception {
    String key = text("server.key");
    for (String invalid :
        List.of(
            key.replaceAll("-{5}END PRIVATE KEY-{5}", ""),
            key.replace("END PRIVATE", "END RSA PRIVATE"),
            key.replace("BEGIN PRIVATE", "BEGIN ENCRYPTED PRIVATE"),
            key.replaceFirst("\\R", ""),
            key + key,
            key + "trailing-source",
            "prefix" + key)) {
      var values = input();
      values.put("LOOKAHEAD_TLS_SERVER_KEY", invalid);
      var failure =
          assertThrows(
              IllegalStateException.class, () -> InjectedCredentialValidator.properties(values));
      assertEquals(
          "Injected credential validation failed; application startup denied",
          failure.getMessage());
      assertNull(failure.getCause());
    }
  }

  @Test
  void aCaCertificateInPlaceOfTheLeafRejects() throws Exception {
    var values = input();
    values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("ca.pem"));
    values.put("LOOKAHEAD_TLS_SERVER_KEY", text("ca.key"));
    assertTrue(
        refused(values)
            .startsWith(
                "Startup refused: LOOKAHEAD_TLS_SERVER_CERTIFICATE starts with a CA certificate; it"
                    + " must start with the leaf certificate"));
  }

  @Test
  void scalarAndMalformedPemBoundaries() throws Exception {
    for (String scalar : List.of("short", "a".repeat(4097), "synthetic-secret\nvalue")) {
      var values = input();
      values.put("LOOKAHEAD_DOMAIN_GATEWAY_SECRET", scalar);
      assertThrows(
          IllegalStateException.class, () -> InjectedCredentialValidator.properties(values));
    }
    for (String pem :
        List.of("", "file:/tmp/forbidden", "x".repeat(65537), text("server.pem").repeat(9))) {
      var values = input();
      values.put("LOOKAHEAD_TLS_SERVER_CERTIFICATE", pem);
      assertThrows(
          IllegalStateException.class, () -> InjectedCredentialValidator.properties(values));
    }
    // DLV-970 (2026-11-17): the Domain has no database secret; a leftover
    // LOOKAHEAD_DOMAIN_DB_PASSWORD is neither required nor read.
    var values = input();
    values.put("LOOKAHEAD_DOMAIN_DB_PASSWORD", "wrongshape-but-long-enough");
    assertFalse(
        InjectedCredentialValidator.properties(values).containsKey("spring.datasource.password"));
  }

  /**
   * DLV-969 (owner, 2026-11-04): a refused injected credential is named by its variable in the log,
   * never by its value.
   */
  @Test
  void aRefusedCredentialIsNamedInTheLogWithoutItsValue() throws Exception {
    command(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "other.key",
        "-out",
        "other.pem",
        "-days",
        "5",
        "-subj",
        "/CN=Untrusted root",
        "-addext",
        "basicConstraints=critical,CA:TRUE");
    leaf("purpose", "/CN=dev.example.com", "dev.example.com", "clientAuth", "3", "server.key");
    leaf("short", "/CN=dev.example.com", "dev.example.com", "serverAuth", "1", "server.key");
    var cases = new LinkedHashMap<String[], String>();
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SCHEMA", "lookahead-tls/v0"},
        "LOOKAHEAD_TLS_SCHEMA is missing or is not lookahead-tls/v1");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SERVER_NAME", "wrong.example.com"},
        "LOOKAHEAD_TLS_SERVER_CERTIFICATE does not list LOOKAHEAD_TLS_SERVER_NAME among its DNS"
            + " names");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SERVER_KEY", text("ca.key")},
        "LOOKAHEAD_TLS_SERVER_KEY does not belong to LOOKAHEAD_TLS_SERVER_CERTIFICATE");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SERVER_KEY", null},
        "LOOKAHEAD_TLS_SERVER_KEY is missing or is not exactly one complete unencrypted PKCS#8 PEM"
            + " private key");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_TRUST", text("server.pem")},
        "LOOKAHEAD_TLS_TRUST is missing, or is not 1 to 8 valid self-signed CA certificates in"
            + " PEM");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SERVER_CERTIFICATE", "not a certificate"},
        "LOOKAHEAD_TLS_SERVER_CERTIFICATE is missing, is not 1 to 8 PEM certificates");
    cases.put(
        new String[] {"LOOKAHEAD_DOMAIN_GATEWAY_SECRET", "short"},
        "LOOKAHEAD_DOMAIN_GATEWAY_SECRET is missing, shorter than 16");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("ca.pem")},
        "LOOKAHEAD_TLS_SERVER_CERTIFICATE starts with a CA certificate; it must start with the leaf"
            + " certificate");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("purpose.pem")},
        "LOOKAHEAD_TLS_SERVER_CERTIFICATE lacks the serverAuth extended key usage");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SERVER_CERTIFICATE", text("short.pem")},
        "LOOKAHEAD_TLS_SERVER_CERTIFICATE expires within a day; renew it");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SERVER_NAME", "Not A Name"},
        "LOOKAHEAD_TLS_SERVER_NAME is missing or is not a lowercase DNS name");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_SERVER_NAME", null},
        "LOOKAHEAD_TLS_SERVER_NAME is missing or is not a lowercase DNS name");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_TRUST", text("other.pem")},
        "LOOKAHEAD_TLS_SERVER_CERTIFICATE does not chain to a CA in LOOKAHEAD_TLS_TRUST");
    cases.put(
        new String[] {"LOOKAHEAD_TLS_TRUST", null},
        "LOOKAHEAD_TLS_TRUST is missing, or is not 1 to 8 valid self-signed CA certificates in"
            + " PEM");
    for (var entry : cases.entrySet()) {
      var values = input();
      values.put(entry.getKey()[0], entry.getKey()[1]);
      try (var log = LogCapture.start("lookahead.startup")) {
        var failure =
            assertThrows(
                IllegalStateException.class, () -> InjectedCredentialValidator.properties(values));
        assertEquals(InjectedCredentialValidator.DENIED, failure.getMessage());
        assertNull(failure.getCause());
        var line = log.only("startup.check");
        assertEquals(Level.ERROR, line.getLevel());
        assertTrue(
            line.getFormattedMessage().startsWith("Startup refused: " + entry.getValue()),
            line.getFormattedMessage());
        assertEquals("INJECTED_CREDENTIAL_INVALID", LogCapture.keys(line).get("reason"));
        log.containsNone(
            "wrong.example.com",
            "dev.example.com",
            "-----BEGIN",
            "synthetic-gateway-secret",
            "synthetic-database-password",
            "lookahead-tls/v0");
      }
    }
  }

  /**
   * A missing LOOKAHEAD_TLS_TRUST is refused by its own name whatever the JVM's own trust store
   * holds: the server pair is only ever measured against the trust input.
   */
  @Test
  void aMissingTrustIsRefusedByNameWhateverTheJvmTrusts() throws Exception {
    String sentence =
        "Startup refused: LOOKAHEAD_TLS_TRUST is missing, or is not 1 to 8 valid self-signed CA"
            + " certificates in PEM";
    var values = input();
    values.remove("LOOKAHEAD_TLS_TRUST");
    // The fixture's JVM trust store holds the synthetic root: it does not stand in for the missing
    // input.
    assertTrue(refused(values).startsWith(sentence));
    // A JVM trust store that does not hold the root: the same refusal, because the JVM's store
    // plays no part.
    command(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "other.key",
        "-out",
        "other.pem",
        "-days",
        "5",
        "-subj",
        "/CN=Untrusted root",
        "-addext",
        "basicConstraints=critical,CA:TRUE");
    KeyStore store = KeyStore.getInstance("JKS");
    store.load(null, null);
    store.setCertificateEntry(
        "other", InjectedCredentialValidator.certificates(text("other.pem")).getFirst());
    try (var output = Files.newOutputStream(directory.resolve("other.jks"))) {
      store.store(output, "changeit".toCharArray());
    }
    System.setProperty("javax.net.ssl.trustStore", directory.resolve("other.jks").toString());
    assertTrue(refused(values).startsWith(sentence));
    // With the trust input present that store still plays no part.
    assertEquals(
        text("ca.pem"),
        InjectedCredentialValidator.properties(input())
            .get("spring.ssl.bundle.pem.serving.truststore.certificate"));
  }

  // ----- DLV-1016 (owner, 2026-11-27): the Domain keeps only its own half of this check -----

  /**
   * The four published properties, their order and their values; the Gateway BFF's values change
   * nothing, well formed or not.
   */
  @Test
  void thePublishedPropertiesAreTheDomainsOwnInputsInTheirOrder() throws Exception {
    var expected = new LinkedHashMap<String, Object>();
    expected.put("spring.ssl.bundle.pem.serving.keystore.certificate", text("server.pem"));
    expected.put("spring.ssl.bundle.pem.serving.keystore.private-key", text("server.key"));
    expected.put("app.cognito.gateway-secret", "synthetic-gateway-secret");
    expected.put("spring.ssl.bundle.pem.serving.truststore.certificate", text("ca.pem"));
    var wellFormed =
        Map.of(
            "LOOKAHEAD_TLS_CLIENT_CERTIFICATE",
            text("client.pem"),
            "LOOKAHEAD_TLS_CLIENT_KEY",
            text("client.key"),
            "APP_OAUTH_CLIENT_ID",
            "SyntheticClient1",
            "LOOKAHEAD_GATEWAY_CLIENT_SECRET",
            "synthetic-oauth-secret");
    var malformed =
        Map.of(
            "LOOKAHEAD_TLS_CLIENT_CERTIFICATE",
            "not a certificate",
            "LOOKAHEAD_TLS_CLIENT_KEY",
            "not a key",
            "APP_OAUTH_CLIENT_ID",
            "invalid-id",
            "LOOKAHEAD_GATEWAY_CLIENT_SECRET",
            "short");
    for (var extra : List.of(Map.<String, String>of(), wellFormed, malformed)) {
      var values = input();
      values.putAll(extra);
      try (var log = LogCapture.start("lookahead.startup")) {
        var properties = InjectedCredentialValidator.properties(values);
        assertEquals(expected, properties, extra.keySet().toString());
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(properties.keySet()));
        assertTrue(log.lines("startup.check").isEmpty());
      }
      // A real fault is refused exactly as without them.
      values.put("LOOKAHEAD_DOMAIN_GATEWAY_SECRET", "short");
      assertTrue(
          refused(values)
              .startsWith(
                  "Startup refused: LOOKAHEAD_DOMAIN_GATEWAY_SECRET is missing, shorter than 16"));
    }
  }

  /**
   * The start reads seven names; each one holding a placeholder with no value is refused by that
   * name.
   */
  @Test
  void everyNameTheStartReadsIsNamedWhenItCannotBeResolved() throws Exception {
    for (String name :
        List.of(
            "LOOKAHEAD_TLS_SCHEMA",
            "LOOKAHEAD_TLS_SERVER_CERTIFICATE",
            "LOOKAHEAD_TLS_SERVER_KEY",
            "LOOKAHEAD_TLS_TRUST",
            "LOOKAHEAD_TLS_SERVER_NAME",
            "APP_OAUTH_CLIENT_ID",
            "LOOKAHEAD_DOMAIN_GATEWAY_SECRET")) {
      try (var context = new GenericApplicationContext();
          var log = LogCapture.start("lookahead.startup")) {
        context.getEnvironment().setActiveProfiles("lower-cost-dev");
        var values = new HashMap<String, Object>(input());
        values.put(name, "${private-marker}");
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(new MapPropertySource("synthetic", values));
        var failure =
            assertThrows(
                IllegalStateException.class,
                () -> new InjectedCredentialInitializer().initialize(context),
                name);
        assertEquals(InjectedCredentialValidator.DENIED, failure.getMessage());
        assertNull(failure.getCause());
        var line = log.only("startup.check");
        assertTrue(
            line.getFormattedMessage()
                .startsWith(
                    "Startup refused: "
                        + name
                        + " could not be read: it holds a ${...} placeholder with no value"),
            line.getFormattedMessage());
        assertEquals("INJECTED_CREDENTIAL_UNRESOLVED", LogCapture.keys(line).get("reason"));
        log.containsNone("private-marker");
      }
    }
  }

  /**
   * The names only the Gateway BFF has are not read: a leftover one that cannot even be resolved
   * does not stop the start.
   */
  @Test
  void theGatewayBffNamesAreNotReadAtTheStart() throws Exception {
    for (String name :
        List.of(
            "LOOKAHEAD_TLS_CLIENT_CERTIFICATE",
            "LOOKAHEAD_TLS_CLIENT_KEY",
            "LOOKAHEAD_GATEWAY_CLIENT_SECRET")) {
      try (var context = new GenericApplicationContext();
          var log = LogCapture.start("lookahead.startup")) {
        context.getEnvironment().setActiveProfiles("lower-cost-dev");
        var values = new HashMap<String, Object>(input());
        values.put(name, "${private-marker}");
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(new MapPropertySource("synthetic", values));
        new InjectedCredentialInitializer().initialize(context);
        var published =
            (MapPropertySource)
                context.getEnvironment().getPropertySources().get("validatedInjectedCredentials");
        assertEquals(
            List.of(
                "spring.ssl.bundle.pem.serving.keystore.certificate",
                "spring.ssl.bundle.pem.serving.keystore.private-key",
                "app.cognito.gateway-secret",
                "spring.ssl.bundle.pem.serving.truststore.certificate"),
            List.of(published.getPropertyNames()),
            name);
        assertTrue(log.lines("startup.check").isEmpty(), name);
      }
    }
  }

  /**
   * DLV-1016 (owner, 2026-11-25): the Domain uses no admission client, so its start neither
   * requires nor reads LOOKAHEAD_ADMISSION_CLIENT_ID and LOOKAHEAD_ADMISSION_CLIENT_SECRET and
   * publishes no app.admission.* property.
   */
  @Test
  void aStartWithoutTheAdmissionValuesIsAccepted() throws Exception {
    var admission = List.of("LOOKAHEAD_ADMISSION_CLIENT_ID", "LOOKAHEAD_ADMISSION_CLIENT_SECRET");
    for (String name : admission) assertFalse(input().containsKey(name), name);
    try (var context = new GenericApplicationContext();
        var log = LogCapture.start("lookahead.startup")) {
      context.getEnvironment().setActiveProfiles("lower-cost-dev");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("synthetic", new HashMap<String, Object>(input())));
      new InjectedCredentialInitializer().initialize(context);
      assertTrue(
          context.getEnvironment().getPropertySources().contains("validatedInjectedCredentials"));
      assertEquals(
          text("server.key"),
          context
              .getEnvironment()
              .getProperty("spring.ssl.bundle.pem.serving.keystore.private-key"));
      assertEquals(
          "synthetic-gateway-secret",
          context.getEnvironment().getProperty("app.cognito.gateway-secret"));
      assertNull(context.getEnvironment().getProperty("app.admission.client-id"));
      assertNull(context.getEnvironment().getProperty("app.admission.client-secret"));
      assertTrue(log.lines("startup.check").isEmpty());
    }
    // Not read either: a leftover admission value that cannot even be resolved does not stop the
    // start any more.
    try (var context = new GenericApplicationContext();
        var log = LogCapture.start("lookahead.startup")) {
      context.getEnvironment().setActiveProfiles("lower-cost-dev");
      var values = new HashMap<String, Object>(input());
      for (String name : admission) values.put(name, "${private-marker}");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("synthetic", values));
      new InjectedCredentialInitializer().initialize(context);
      assertTrue(
          context.getEnvironment().getPropertySources().contains("validatedInjectedCredentials"));
      assertTrue(log.lines("startup.check").isEmpty());
    }
    var properties = InjectedCredentialValidator.properties(input());
    assertFalse(properties.keySet().stream().anyMatch(key -> key.startsWith("app.admission")));
    assertEquals("synthetic-gateway-secret", properties.get("app.cognito.gateway-secret"));
  }

  @Test
  void anUnresolvedInjectedValueIsNamedBeforeStartupStops() {
    try (var context = new GenericApplicationContext();
        var log = LogCapture.start("lookahead.startup")) {
      context.getEnvironment().setActiveProfiles("lower-cost-dev");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "synthetic", Map.of("LOOKAHEAD_TLS_TRUST", "${private-marker}")));
      assertThrows(
          IllegalStateException.class,
          () -> new InjectedCredentialInitializer().initialize(context));
      var line = log.only("startup.check");
      assertTrue(
          line.getFormattedMessage()
              .startsWith(
                  "Startup refused: LOOKAHEAD_TLS_TRUST could not be read: it holds a ${...}"
                      + " placeholder with no value"),
          line.getFormattedMessage());
      assertEquals("INJECTED_CREDENTIAL_UNRESOLVED", LogCapture.keys(line).get("reason"));
      log.containsNone("private-marker");
    }
  }
}
