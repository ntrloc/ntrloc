package org.ntrloc.gateway.registry;

import org.ntrloc.gateway.api.DomainInstanceInfo;

public interface DomainRegistry {

    void register(DomainInstanceInfo info);

}
