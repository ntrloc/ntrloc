package org.nterloc.gateway.registry;

import org.nterloc.gateway.api.DomainInstanceInfo;

public interface DomainRegistry {

    void register(DomainInstanceInfo info);

}
