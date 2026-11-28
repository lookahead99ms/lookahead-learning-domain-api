package com.lookahead.domain.security.tls;

import com.lookahead.domain.log.EventLog;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Startup validation only: no secret retrieval, files or private exception causes. DLV-969 (owner,
 * 2026-11-04): a refusal writes one {@code lookahead.startup} line naming the injected input by its
 * environment variable and what is wrong with it ({@link com.lookahead.domain.log.EventLog}), never
 * a value; the exception keeps its fixed text.
 */
public final class InjectedCredentialValidator {
  static final String DENIED = "Injected credential validation failed; application startup denied";

  private InjectedCredentialValidator() {}

  /**
   * The input being checked and what is wrong with it if this check fails: names only, never
   * values.
   */
  static final class Check {
    String input;
    String problem;

    void at(String input, String problem) {
      this.input = input;
      this.problem = problem;
    }
  }

  /**
   * Runs every check of the Domain's injected inputs in a fixed order and returns the properties
   * they are published under.
   */
  public static Map<String, Object> properties(Map<String, String> input) {
    var check = new Check();
    try {
      check.at("LOOKAHEAD_TLS_SCHEMA", "is missing or is not lookahead-tls/v1");
      if (!"lookahead-tls/v1".equals(input.get("LOOKAHEAD_TLS_SCHEMA")))
        throw new IllegalArgumentException();
      String certificate = input.get("LOOKAHEAD_TLS_SERVER_CERTIFICATE");
      String key = input.get("LOOKAHEAD_TLS_SERVER_KEY");
      String trust = input.get("LOOKAHEAD_TLS_TRUST");
      validateServerPair(certificate, key, trust, input.get("LOOKAHEAD_TLS_SERVER_NAME"), check);
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("spring.ssl.bundle.pem.serving.keystore.certificate", certificate);
      values.put("spring.ssl.bundle.pem.serving.keystore.private-key", key);
      check.at("LOOKAHEAD_DOMAIN_GATEWAY_SECRET", SCALAR);
      values.put(
          "app.cognito.gateway-secret", scalar(input.get("LOOKAHEAD_DOMAIN_GATEWAY_SECRET")));
      // DLV-970 (2026-11-17): the Domain has no database secret any more; its server bundle trusts
      // the Gateway BFF's CA.
      values.put("spring.ssl.bundle.pem.serving.truststore.certificate", trust);
      return values;
    } catch (Exception rejected) {
      // Parse/binding exceptions may contain PEM or scalar values. Do not retain the cause; log its
      // types only.
      EventLog.startupRefused(check.input, "INJECTED_CREDENTIAL_INVALID", check.problem, rejected);
      throw new IllegalStateException(DENIED);
    }
  }

  static final String SCALAR =
      "is missing, shorter than 16 or longer than 4096 characters, or contains control characters";

  static String scalar(String value) {
    if (value == null
        || value.length() < 16
        || value.length() > 4096
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException();
    return value;
  }

  static List<X509Certificate> certificates(String pem) throws Exception {
    if (pem == null || pem.length() > 65536) throw new IllegalArgumentException();
    var values =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    if (values.isEmpty() || values.size() > 8) throw new IllegalArgumentException();
    List<X509Certificate> result = new ArrayList<>();
    for (var value : values) {
      var certificate = (X509Certificate) value;
      certificate.checkValidity();
      result.add(certificate);
    }
    return result;
  }

  static Set<TrustAnchor> trustAnchors(String pem) throws Exception {
    Set<TrustAnchor> anchors = new HashSet<>();
    for (var cert : certificates(pem)) {
      if (cert.getBasicConstraints() < 0
          || !cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()))
        throw new IllegalArgumentException();
      cert.verify(cert.getPublicKey());
      anchors.add(new TrustAnchor(cert, null));
    }
    return anchors;
  }

  /**
   * The server certificate, its private key, the name it must serve and the CA certificates it must
   * chain to; {@code check} names the input being checked. Only LOOKAHEAD_TLS_TRUST is trusted: the
   * JVM's own trust store plays no part.
   */
  static void validateServerPair(
      String pem, String keyPem, String trustPem, String expectedName, Check check)
      throws Exception {
    String certificate = "LOOKAHEAD_TLS_SERVER_CERTIFICATE";
    String key = "LOOKAHEAD_TLS_SERVER_KEY";
    String name = "LOOKAHEAD_TLS_SERVER_NAME";
    check.at(
        certificate,
        "is missing, is not 1 to 8 PEM certificates, or holds one that is expired or not yet"
            + " valid");
    var chain = certificates(pem);
    var leaf = chain.getFirst();
    check.at(certificate, "starts with a CA certificate; it must start with the leaf certificate");
    if (leaf.getBasicConstraints() >= 0) throw new IllegalArgumentException();
    check.at(name, "is missing or is not a lowercase DNS name");
    if (expectedName == null || !expectedName.matches("[a-z0-9][a-z0-9.-]+"))
      throw new IllegalArgumentException();
    check.at(certificate, "expires within a day; renew it");
    if (leaf.getNotAfter().toInstant().isBefore(Instant.now().plus(Duration.ofDays(1))))
      throw new IllegalArgumentException();
    check.at(certificate, "does not list " + name + " among its DNS names");
    var sans = leaf.getSubjectAlternativeNames();
    if (sans == null
        || sans.stream()
            .noneMatch(s -> Integer.valueOf(2).equals(s.get(0)) && expectedName.equals(s.get(1))))
      throw new IllegalArgumentException();
    check.at(certificate, "lacks the serverAuth extended key usage");
    if (leaf.getExtendedKeyUsage() == null
        || !leaf.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"))
      throw new IllegalArgumentException();
    // Match a complete PKCS8 PEM envelope; never accept extra blocks or trailing material.
    check.at(
        key,
        "is missing or is not exactly one complete unencrypted PKCS#8 PEM private key (BEGIN"
            + " PRIVATE KEY)");
    var keyBlock =
        Pattern.compile(
                "\\A-{5}BEGIN PRIVATE KEY-{5}\\R([A-Za-z0-9+/=\\r\\n]+)-{5}END PRIVATE"
                    + " KEY-{5}[\\r\\n]*\\z")
            .matcher(keyPem);
    if (!keyBlock.matches()) throw new IllegalArgumentException();
    check.at(key, "does not belong to " + certificate);
    byte[] encoded = Base64.getMimeDecoder().decode(keyBlock.group(1));
    var privateKey =
        KeyFactory.getInstance(leaf.getPublicKey().getAlgorithm())
            .generatePrivate(new PKCS8EncodedKeySpec(encoded));
    var signature =
        Signature.getInstance(
            leaf.getPublicKey().getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA");
    byte[] challenge = new byte[32];
    new SecureRandom().nextBytes(challenge);
    signature.initSign(privateKey);
    signature.update(challenge);
    byte[] signed = signature.sign();
    signature.initVerify(leaf.getPublicKey());
    signature.update(challenge);
    if (!signature.verify(signed)) throw new IllegalArgumentException();
    check.at(
        "LOOKAHEAD_TLS_TRUST",
        "is missing, or is not 1 to 8 valid self-signed CA certificates in PEM");
    Set<TrustAnchor> anchors = trustAnchors(trustPem);
    check.at(certificate, "does not chain to a CA in LOOKAHEAD_TLS_TRUST");
    var selector = new X509CertSelector();
    selector.setCertificate(leaf);
    var parameters = new PKIXBuilderParameters(anchors, selector);
    parameters.setRevocationEnabled(false);
    parameters.addCertStore(
        CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
    CertPathBuilder.getInstance("PKIX").build(parameters);
  }
}
