# Public RDS trust roots

`rds-global-bundle.pem` is the unmodified public commercial-region bundle downloaded from AWS on2026-10-08. `provenance.json` records its official source, documentation, digest and runtime path. It contains111 root certificates and no private keys. The Docker build verifies the SHA-256 pin and each root's validity, CA constraint and self-signature using `VerifyRdsTrust.java`; the runtime image receives only the public PEM at `/opt/lookahead/trust/rds-global-bundle.pem`.

The exact PEM exception in Git/Docker ignore rules does not allow other PEM, private key, keystore or secret files. No certificate download happens at application startup. Use `sslmode=verify-full&sslrootcert=/opt/lookahead/trust/rds-global-bundle.pem` with the exact regional RDS endpoint and `lookahead_platform` database. Local settings are unchanged.

To update trust roots, fetch the official AWS URL over verified HTTPS, review the changed roots and validity, update the checksum and provenance together, rebuild and verify the image, rerun cloud validation tests, then obtain the usual release review. Do not bypass the checksum or hostname verification after a connection failure. The checksum establishes reproducible reviewed bytes; it is not a separate AWS signature or proof of a live RDS connection.

Sources: [AWS bundle guidance](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html), [public bundle](https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem).
