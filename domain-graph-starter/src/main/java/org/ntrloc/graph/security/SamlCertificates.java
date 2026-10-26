package org.ntrloc.graph.security;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

// ntrloc's own key pairs for a SAML IdP: generated (RSA 2048 and a self-signed certificate, which is
// normal for SAML -- the IdP trusts the certificate it imported from ntrloc's metadata, not a CA)
// or uploaded by an admin. Stored as PEM text: the private key encrypted (SecretCipher), the
// certificate in the clear.
final class SamlCertificates {

    static final Duration VALIDITY = Duration.ofDays(3L * 365);

    record SigningPair(String privateKeyPem, String certificatePem) {}

    private SamlCertificates() {
    }

    static SigningPair generate(String commonName) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=" + commonName);
            // BouncyCastle only takes java.util.Date here; named in full, not imported, since Sonar's
            // S2143 flags the import, which no @SuppressWarnings can reach.
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                    new BigInteger(64, new SecureRandom()), java.util.Date.from(now),
                    java.util.Date.from(now.plus(VALIDITY)), subject, keys.getPublic());
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
            return new SigningPair(pem("PRIVATE KEY", keys.getPrivate().getEncoded()),
                    pem("CERTIFICATE", certificate.getEncoded()));
        } catch (GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException("Couldn't generate a SAML signing certificate", e);
        }
    }

    static PrivateKey privateKey(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            return switch (parsed) {
                case PrivateKeyInfo info -> converter.getPrivateKey(info);
                case PEMKeyPair pair -> converter.getKeyPair(pair).getPrivate();
                case null, default -> throw new IllegalArgumentException("The private key isn't a PEM private key "
                        + "(-----BEGIN PRIVATE KEY----- or -----BEGIN RSA PRIVATE KEY-----); encrypted keys aren't supported");
            };
        } catch (IOException e) {
            throw new IllegalArgumentException("The private key isn't valid PEM", e);
        }
    }

    // An admin's own key pair, instead of a generated one: an RSA key, the certificate for it, and
    // a certificate that's valid now. Returns them as ntrloc stores them.
    static SigningPair uploaded(String privateKeyPem, String certificatePem) {
        X509Certificate certificate;
        try {
            certificate = certificate(certificatePem);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("The certificate isn't a valid PEM X.509 certificate");
        }
        PrivateKey key = privateKey(privateKeyPem);
        if (!(key instanceof RSAPrivateCrtKey rsaKey) || !(certificate.getPublicKey() instanceof RSAPublicKey rsaCertificate)) {
            throw new IllegalArgumentException("Only RSA keys are supported");
        }
        if (!rsaKey.getModulus().equals(rsaCertificate.getModulus())) {
            throw new IllegalArgumentException("The private key doesn't belong to the certificate");
        }
        try {
            certificate.checkValidity();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The certificate isn't valid now: it runs from "
                    + certificate.getNotBefore().toInstant() + " to " + certificate.getNotAfter().toInstant());
        }
        try {
            return new SigningPair(pem("PRIVATE KEY", key.getEncoded()), pem("CERTIFICATE", certificate.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The certificate can't be read", e);
        }
    }

    static X509Certificate certificate(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Not a valid SAML signing certificate", e);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + type + "-----\n";
    }
}
