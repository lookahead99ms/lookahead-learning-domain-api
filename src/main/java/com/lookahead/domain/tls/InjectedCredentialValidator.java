package com.lookahead.domain.tls;

import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Startup validation only: no secret retrieval, files, logging or private exception causes. */
public final class InjectedCredentialValidator {
    private InjectedCredentialValidator() { }

    public static Map<String, Object> properties(Map<String, String> input, String service) {
        try {
            if (!Set.of("gateway", "domain").contains(service)
                    || !"lookahead-tls/v1".equals(input.get("LOOKAHEAD_TLS_SCHEMA"))) throw new IllegalArgumentException();
            String certificate = input.get("LOOKAHEAD_TLS_SERVER_CERTIFICATE");
            String key = input.get("LOOKAHEAD_TLS_SERVER_KEY");
            String trust = input.get("LOOKAHEAD_TLS_TRUST");
            validatePair(certificate, key, service.equals("gateway") ? null : trust,
                    input.get("LOOKAHEAD_TLS_SERVER_NAME"), false);
            trustAnchors(trust);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("spring.ssl.bundle.pem.serving.keystore.certificate", certificate);
            values.put("spring.ssl.bundle.pem.serving.keystore.private-key", key);
            values.put("app.cognito.gateway-secret", scalar(input.get("LOOKAHEAD_DOMAIN_GATEWAY_SECRET")));
            if (service.equals("gateway")) {
                String client = input.get("LOOKAHEAD_TLS_CLIENT_CERTIFICATE");
                String clientKey = input.get("LOOKAHEAD_TLS_CLIENT_KEY");
                validatePair(client, clientKey, trust, "gateway.lookahead-dev.internal", true);
                if (Arrays.equals(certificates(certificate).getFirst().getPublicKey().getEncoded(),
                        certificates(client).getFirst().getPublicKey().getEncoded())) throw new IllegalArgumentException();
                values.put("spring.ssl.bundle.pem.internal.keystore.certificate", client);
                values.put("spring.ssl.bundle.pem.internal.keystore.private-key", clientKey);
                values.put("spring.ssl.bundle.pem.internal.truststore.certificate", trust);
                values.put("app.oauth.client-id", clientId(input.get("APP_OAUTH_CLIENT_ID")));
                values.put("app.oauth.client-secret", scalar(input.get("LOOKAHEAD_GATEWAY_CLIENT_SECRET")));
                values.put("app.admission.client-id", clientId(input.get("LOOKAHEAD_ADMISSION_CLIENT_ID")));
                values.put("app.admission.client-secret", scalar(input.get("LOOKAHEAD_ADMISSION_CLIENT_SECRET")));
            } else {
                String password = scalar(input.get("LOOKAHEAD_DOMAIN_DB_PASSWORD"));
                if (!password.matches("[A-Za-z0-9]{48}")) throw new IllegalArgumentException();
                values.put("spring.datasource.password", password);
                values.put("spring.ssl.bundle.pem.serving.truststore.certificate", trust);
            }
            return values;
        } catch (Exception rejected) {
            // Parse/binding exceptions may contain PEM or scalar values. Do not retain the cause.
            throw new IllegalStateException("Injected credential validation failed; application startup denied");
        }
    }

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
        var chain = certificates(pem); var leaf = chain.getFirst();
        if (leaf.getBasicConstraints() >= 0 || expectedName == null || !expectedName.matches("[a-z0-9][a-z0-9.-]+")
                || leaf.getNotAfter().toInstant().isBefore(Instant.now().plus(Duration.ofDays(1)))) throw new IllegalArgumentException();
        var sans = leaf.getSubjectAlternativeNames();
        if (sans == null || sans.stream().noneMatch(s -> Integer.valueOf(2).equals(s.get(0)) && expectedName.equals(s.get(1)))) throw new IllegalArgumentException();
        // NGINX deliberately authorizes the exact Gateway subject. Reject extra
        // RDNs here rather than install credentials that its peer will reject.
        if (client && !leaf.getSubjectX500Principal().equals(
                new javax.security.auth.x500.X500Principal("CN=" + expectedName))) throw new IllegalArgumentException();
        if (leaf.getExtendedKeyUsage() == null || !leaf.getExtendedKeyUsage().contains(client ? "1.3.6.1.5.5.7.3.2" : "1.3.6.1.5.5.7.3.1")) throw new IllegalArgumentException();
        // Match a complete PKCS8 PEM envelope; never accept extra blocks or trailing material.
        var keyBlock = java.util.regex.Pattern.compile(
                "\\A-{5}BEGIN PRIVATE KEY-{5}\\R([A-Za-z0-9+/=\\r\\n]+)-{5}END PRIVATE KEY-{5}[\\r\\n]*\\z").matcher(keyPem);
        if (!keyBlock.matches()) throw new IllegalArgumentException();
        byte[] encoded = Base64.getMimeDecoder().decode(keyBlock.group(1));
        var privateKey = KeyFactory.getInstance(leaf.getPublicKey().getAlgorithm()).generatePrivate(new PKCS8EncodedKeySpec(encoded));
        var signature = Signature.getInstance(leaf.getPublicKey().getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA");
        byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
        signature.initSign(privateKey); signature.update(challenge); byte[] signed = signature.sign();
        signature.initVerify(leaf.getPublicKey()); signature.update(challenge); if (!signature.verify(signed)) throw new IllegalArgumentException();
        Set<TrustAnchor> anchors;
        if (trustPem != null) anchors = trustAnchors(trustPem);
        else {
            var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init((KeyStore) null);
            anchors = new HashSet<>();
            for (var manager : factory.getTrustManagers()) if (manager instanceof X509TrustManager x509)
                for (var cert : x509.getAcceptedIssuers()) anchors.add(new TrustAnchor(cert, null));
        }
        var selector = new X509CertSelector(); selector.setCertificate(leaf);
        var parameters = new PKIXBuilderParameters(anchors, selector); parameters.setRevocationEnabled(false);
        parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
        CertPathBuilder.getInstance("PKIX").build(parameters);
    }
}
