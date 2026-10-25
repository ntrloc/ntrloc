package org.ntrloc.ui;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * Lets this test JVM resolve host.testcontainers.internal -- the name containers use to reach this
 * machine -- to loopback, so a browser in a container and the server in this JVM can reach another
 * container (an identity provider) at one and the same URL. Every other name goes to the JVM's
 * built-in resolver. Registered in META-INF/services/java.net.spi.InetAddressResolverProvider.
 */
public class TestcontainersHostResolverProvider extends InetAddressResolverProvider {

    static final String HOST = "host.testcontainers.internal";

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
                if (HOST.equalsIgnoreCase(host)) {
                    return Stream.of(InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1}));
                }
                return builtin.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                return builtin.lookupByAddress(address);
            }
        };
    }

    @Override
    public String name() {
        return "testcontainers-host";
    }
}
