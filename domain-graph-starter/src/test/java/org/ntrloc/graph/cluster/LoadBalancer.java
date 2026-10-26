package org.ntrloc.graph.cluster;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A real nginx in front of the cluster, set up the way ingress-nginx fronts a Kubernetes service:
 * it terminates TLS (a certificate made for this run), passes the browser's Host header through,
 * adds X-Forwarded-For/-Proto/-Host/-Port, doesn't buffer responses (so server-sent events
 * stream), and spreads requests round-robin across the nodes. It listens on the same port inside
 * and outside its container, as a real balancer's public port is the one it listens on, so the
 * port it forwards is the one the browser used.
 *
 * One addition a real balancer wouldn't have: a request carrying X-Test-Node: a or b goes to that
 * node, so a test can make a sign-in start on one node and finish on the other. nginx's access
 * log records which node served each request.
 */
final class LoadBalancer implements AutoCloseable {

    static final String NODE_HEADER = "X-Test-Node";
    static final String NODE_A = "a";
    static final String NODE_B = "b";

    private static final String HOST = "host.testcontainers.internal";
    // nginx logs the address it resolved the node's host name to, then the port.
    private static final Pattern SERVED_BY = Pattern.compile("served-by=\\S+:(\\d+)");

    private final int port;
    private final X509Certificate certificate;
    private final GenericContainer<?> nginx;

    @SuppressWarnings({"resource", "deprecation"})
    LoadBalancer(int nodePortA, int nodePortB) {
        Testcontainers.exposeHostPorts(nodePortA, nodePortB);
        port = freePort();
        KeyPair keys = rsaKeys();
        certificate = selfSigned(keys);
        nginx = new FixedHostPortGenericContainer<>("nginx:1.28.0-alpine")
                .withFixedExposedPort(port, port)
                .withCopyToContainer(Transferable.of(config(nodePortA, nodePortB)), "/etc/nginx/nginx.conf")
                .withCopyToContainer(Transferable.of(pem(certificate)), "/etc/nginx/tls/cert.pem")
                .withCopyToContainer(Transferable.of(pem(keys.getPrivate())), "/etc/nginx/tls/key.pem")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(1)));
        nginx.start();
    }

    /** The public URL for a path, as a browser would use it. */
    String url(String path) {
        return "https://localhost:" + port + path;
    }

    /** Trusts this run's certificate, as a browser trusts a real balancer's. */
    SSLContext trust() {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("load-balancer", certificate);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The node ports that have served requests to this path, from nginx's access log. */
    Set<Integer> nodesThatServed(String path) {
        return nginx.getLogs().lines()
                .filter(line -> line.contains(" " + path + " "))
                .map(SERVED_BY::matcher)
                .filter(Matcher::find)
                .map(match -> Integer.parseInt(match.group(1)))
                .collect(Collectors.toSet());
    }

    @Override
    public void close() {
        nginx.stop();
    }

    private String config(int nodePortA, int nodePortB) {
        return """
                events {}
                http {
                    log_format routed '$request_method $request_uri served-by=$upstream_addr';
                    access_log /dev/stdout routed;

                    upstream cluster {
                        server %1$s:%2$d;
                        server %1$s:%3$d;
                    }
                    upstream node_a { server %1$s:%2$d; }
                    upstream node_b { server %1$s:%3$d; }

                    map $http_x_test_node $nodes {
                        a       node_a;
                        b       node_b;
                        default cluster;
                    }

                    server {
                        listen %4$d ssl;
                        ssl_certificate     /etc/nginx/tls/cert.pem;
                        ssl_certificate_key /etc/nginx/tls/key.pem;

                        location / {
                            proxy_pass http://$nodes;
                            proxy_http_version 1.1;
                            proxy_set_header Connection "";
                            proxy_set_header Host $http_host;
                            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                            proxy_set_header X-Forwarded-Proto $scheme;
                            proxy_set_header X-Forwarded-Host $http_host;
                            proxy_set_header X-Forwarded-Port $server_port;
                            proxy_buffering off;
                            proxy_read_timeout 1h;
                        }
                    }
                }
                """.formatted(HOST, nodePortA, nodePortB, port);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static KeyPair rsaKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // A certificate for localhost, the name the browser uses.
    private static X509Certificate selfSigned(KeyPair keys) {
        try {
            X500Name subject = new X500Name("CN=localhost");
            Instant now = Instant.now();
            X509CertificateHolder holder = new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now.toEpochMilli()),
                    Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(1))), subject, keys.getPublic())
                    .addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                            new GeneralName(GeneralName.dNSName, "localhost"),
                            new GeneralName(GeneralName.iPAddress, "127.0.0.1")}))
                    .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()));
            return new JcaX509CertificateConverter().getCertificate(holder);
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't make the load balancer's certificate", e);
        }
    }

    private static String pem(Object object) {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(object);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }
}
