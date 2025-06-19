package org.ntrloc.gateway;

import org.ntrloc.gateway.api.DomainInstanceInfo;
import org.ntrloc.gateway.registry.DomainRegistry;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/registration")
public class RegistrationController {

    private final DomainRegistry domainRegistry;

    public RegistrationController(DomainRegistry domainRegistry) {
        this.domainRegistry = domainRegistry;
    }

    @PutMapping
    public void register(@RequestBody DomainInstanceInfo info) {
        domainRegistry.register(info);
    }

}
