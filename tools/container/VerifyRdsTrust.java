import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/** Build-time verification of the checksum-pinned public RDS root bundle. */
class VerifyRdsTrust {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one public CA bundle");
        Path bundle = Path.of(args[0]);
        String text = Files.readString(bundle);
        if (text.contains("PRIVATE KEY") || Files.size(bundle) > 1024 * 1024) {
            throw new IllegalArgumentException("Invalid public CA bundle");
        }
        try (var input = Files.newInputStream(bundle)) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
            if (certificates.isEmpty()) throw new IllegalArgumentException("Empty CA bundle");
            for (var certificate : certificates) {
                X509Certificate root = (X509Certificate) certificate;
                root.checkValidity();
                if (root.getBasicConstraints() < 0 || !root.getSubjectX500Principal().equals(root.getIssuerX500Principal())) {
                    throw new IllegalArgumentException("Only root CA certificates are permitted");
                }
                root.verify(root.getPublicKey());
            }
            System.out.println("Verified " + certificates.size() + " public RDS root certificates");
        }
    }
}
