package com.lookahead.domain.tls;

import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import com.lookahead.domain.StartupChecks;
import java.util.*;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Startup validation only: no secret retrieval, files or private exception causes. DLV-969 (owner, 2026-11-04): a
 * refusal writes one {@code lookahead.startup} line naming the injected input by its environment variable and what is
 * wrong with it ({@link StartupChecks}), never a value; the exception keeps its fixed text.
 */
public final class InjectedCredentialValidator {
    static final String DENIED = "Injected credential validation failed; application startup denied";
    private InjectedCredentialValidator() { }

    /** The input being checked and what is wrong with it if this check fails: names only, never values. */
    static final class Check {
        String input = "service";
        String problem = "is not gateway or domain";
        void at(String input, String problem) { this.input = input; this.problem = problem; }
    }

    public static Map<String, Object> properties(Map<String, String> input, String service) {
        var check = new Check();
        try {
            if (!Set.of("gateway", "domain").contains(service)) throw new IllegalArgumentException();
            check.at("LOOKAHEAD_TLS_SCHEMA", "is missing or is not lookahead-tls/v1");
            if (!"lookahead-tls/v1".equals(input.get("LOOKAHEAD_TLS_SCHEMA"))) throw new IllegalArgumentException();
            String certificate = input.get("LOOKAHEAD_TLS_SERVER_CERTIFICATE");
            String key = input.get("LOOKAHEAD_TLS_SERVER_KEY");
            String trust = input.get("LOOKAHEAD_TLS_TRUST");
            validatePair(certificate, key, service.equals("gateway") ? null : trust,
                    input.get("LOOKAHEAD_TLS_SERVER_NAME"), false, check, "LOOKAHEAD_TLS_SERVER_CERTIFICATE", "LOOKAHEAD_TLS_SERVER_KEY",
                    "LOOKAHEAD_TLS_SERVER_NAME");
            check.at("LOOKAHEAD_TLS_TRUST", "is missing, or is not 1 to 8 valid self-signed CA certificates in PEM");
            trustAnchors(trust);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("spring.ssl.bundle.pem.serving.keystore.certificate", certificate);
            values.put("spring.ssl.bundle.pem.serving.keystore.private-key", key);
            check.at("LOOKAHEAD_DOMAIN_GATEWAY_SECRET", SCALAR);
            values.put("app.cognito.gateway-secret", scalar(input.get("LOOKAHEAD_DOMAIN_GATEWAY_SECRET")));
            if (service.equals("gateway")) {
                String client = input.get("LOOKAHEAD_TLS_CLIENT_CERTIFICATE");
                String clientKey = input.get("LOOKAHEAD_TLS_CLIENT_KEY");
                validatePair(client, clientKey, trust, "gateway.lookahead-dev.internal", true, check, "LOOKAHEAD_TLS_CLIENT_CERTIFICATE",
                        "LOOKAHEAD_TLS_CLIENT_KEY", "the Gateway client name");
                check.at("LOOKAHEAD_TLS_CLIENT_CERTIFICATE", "uses the same key as LOOKAHEAD_TLS_SERVER_CERTIFICATE; the client needs its own key");
                if (Arrays.equals(certificates(certificate).getFirst().getPublicKey().getEncoded(),
                        certificates(client).getFirst().getPublicKey().getEncoded())) throw new IllegalArgumentException();
                values.put("spring.ssl.bundle.pem.internal.keystore.certificate", client);
                values.put("spring.ssl.bundle.pem.internal.keystore.private-key", clientKey);
                values.put("spring.ssl.bundle.pem.internal.truststore.certificate", trust);
                check.at("APP_OAUTH_CLIENT_ID", CLIENT_ID);
                values.put("app.oauth.client-id", clientId(input.get("APP_OAUTH_CLIENT_ID")));
                check.at("LOOKAHEAD_GATEWAY_CLIENT_SECRET", SCALAR);
                values.put("app.oauth.client-secret", scalar(input.get("LOOKAHEAD_GATEWAY_CLIENT_SECRET")));
                check.at("LOOKAHEAD_ADMISSION_CLIENT_ID", CLIENT_ID);
                values.put("app.admission.client-id", clientId(input.get("LOOKAHEAD_ADMISSION_CLIENT_ID")));
                check.at("LOOKAHEAD_ADMISSION_CLIENT_SECRET", SCALAR);
                values.put("app.admission.client-secret", scalar(input.get("LOOKAHEAD_ADMISSION_CLIENT_SECRET")));
            } else {
                check.at("LOOKAHEAD_DOMAIN_DB_PASSWORD", "is missing or is not exactly 48 letters and digits");
                String password = scalar(input.get("LOOKAHEAD_DOMAIN_DB_PASSWORD"));
                if (!password.matches("[A-Za-z0-9]{48}")) throw new IllegalArgumentException();
                values.put("spring.datasource.password", password);
                values.put("spring.ssl.bundle.pem.serving.truststore.certificate", trust);
            }
            return values;
        } catch (Exception rejected) {
            // Parse/binding exceptions may contain PEM or scalar values. Do not retain the cause; log its types only.
            StartupChecks.refused(check.input, "INJECTED_CREDENTIAL_INVALID", check.problem, rejected);
            throw new IllegalStateException(DENIED);
        }
    }

    static final String SCALAR = "is missing, shorter than 16 or longer than 4096 characters, or contains control characters";
    static final String CLIENT_ID = "is missing or is not 1 to 128 letters and digits";

    static String scalar(String value) {
        if (value == null || value.length() < 16 || value.length() > 4096
                || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return value;
    }

    static String clientId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9]{1,128}")) throw new IllegalArgumentException();
        return value;
    }
    static List<X509Certificate> certificates(String pem) throws Exception {
        if (pem == null || pem.length() > 65536) throw new IllegalArgumentException();
        var values = CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        if (values.isEmpty() || values.size() > 8) throw new IllegalArgumentException();
        List<X509Certificate> result = new ArrayList<>();
        for (var value : values) { var certificate = (X509Certificate) value; certificate.checkValidity(); result.add(certificate); }
        return result;
    }
    static Set<TrustAnchor> trustAnchors(String pem) throws Exception {
        Set<TrustAnchor> anchors = new HashSet<>();
        for (var cert : certificates(pem)) {
            if (cert.getBasicConstraints() < 0 || !cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) throw new IllegalArgumentException();
            cert.verify(cert.getPublicKey()); anchors.add(new TrustAnchor(cert, null));
        }
        return anchors;
    }
    public static void validatePair(String pem, String keyPem, String trustPem, String expectedName, boolean client) throws Exception {
        validatePair(pem, keyPem, trustPem, expectedName, client, new Check(), "certificate", "private key", "server name");
    }

    /** As above; {@code check} names the input being checked, using the given input names. */
    static void validatePair(String pem, String keyPem, String trustPem, String expectedName, boolean client, Check check,
            String certificate, String key, String name) throws Exception {
        check.at(certificate, "is missing, is not 1 to 8 PEM certificates, or holds one that is expired or not yet valid");
        var chain = certificates(pem); var leaf = chain.getFirst();
        check.at(certificate, "starts with a CA certificate; it must start with the leaf certificate");
        if (leaf.getBasicConstraints() >= 0) throw new IllegalArgumentException();
        check.at(name, "is missing or is not a lowercase DNS name");
        if (expectedName == null || !expectedName.matches("[a-z0-9][a-z0-9.-]+")) throw new IllegalArgumentException();
        check.at(certificate, "expires within a day; renew it");
        if (leaf.getNotAfter().toInstant().isBefore(Instant.now().plus(Duration.ofDays(1)))) throw new IllegalArgumentException();
        check.at(certificate, "does not list " + name + " among its DNS names");
        var sans = leaf.getSubjectAlternativeNames();
        if (sans == null || sans.stream().noneMatch(s -> Integer.valueOf(2).equals(s.get(0)) && expectedName.equals(s.get(1)))) throw new IllegalArgumentException();
        // NGINX deliberately authorizes the exact Gateway subject. Reject extra
        // RDNs here rather than install credentials that its peer will reject.
        check.at(certificate, "has a subject other than exactly CN=<the Gateway name>");
        if (client && !leaf.getSubjectX500Principal().equals(
                new javax.security.auth.x500.X500Principal("CN=" + expectedName))) throw new IllegalArgumentException();
        check.at(certificate, client ? "lacks the clientAuth extended key usage" : "lacks the serverAuth extended key usage");
        if (leaf.getExtendedKeyUsage() == null || !leaf.getExtendedKeyUsage().contains(client ? "1.3.6.1.5.5.7.3.2" : "1.3.6.1.5.5.7.3.1")) throw new IllegalArgumentException();
        // Match a complete PKCS8 PEM envelope; never accept extra blocks or trailing material.
        check.at(key, "is missing or is not exactly one complete unencrypted PKCS#8 PEM private key (BEGIN PRIVATE KEY)");
        var keyBlock = java.util.regex.Pattern.compile(
                "\\A-{5}BEGIN PRIVATE KEY-{5}\\R([A-Za-z0-9+/=\\r\\n]+)-{5}END PRIVATE KEY-{5}[\\r\\n]*\\z").matcher(keyPem);
        if (!keyBlock.matches()) throw new IllegalArgumentException();
        check.at(key, "does not belong to " + certificate);
        byte[] encoded = Base64.getMimeDecoder().decode(keyBlock.group(1));
        var privateKey = KeyFactory.getInstance(leaf.getPublicKey().getAlgorithm()).generatePrivate(new PKCS8EncodedKeySpec(encoded));
        var signature = Signature.getInstance(leaf.getPublicKey().getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA");
        byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
        signature.initSign(privateKey); signature.update(challenge); byte[] signed = signature.sign();
        signature.initVerify(leaf.getPublicKey()); signature.update(challenge); if (!signature.verify(signed)) throw new IllegalArgumentException();
        Set<TrustAnchor> anchors;
        check.at("LOOKAHEAD_TLS_TRUST", "is missing, or is not 1 to 8 valid self-signed CA certificates in PEM");
        if (trustPem != null) anchors = trustAnchors(trustPem);
        else {
            var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init((KeyStore) null);
            anchors = new HashSet<>();
            for (var manager : factory.getTrustManagers()) if (manager instanceof X509TrustManager x509)
                for (var cert : x509.getAcceptedIssuers()) anchors.add(new TrustAnchor(cert, null));
        }
        check.at(certificate, trustPem != null ? "does not chain to a CA in LOOKAHEAD_TLS_TRUST" : "does not chain to a CA the JVM trusts");
        var selector = new X509CertSelector(); selector.setCertificate(leaf);
        var parameters = new PKIXBuilderParameters(anchors, selector); parameters.setRevocationEnabled(false);
        parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
        CertPathBuilder.getInstance("PKIX").build(parameters);
    }
}
