package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.ServiceConfigurator;
import org.flowable.identitylink.service.IdentityLinkServiceConfiguration;

// Same beforeInit()-hook reasoning as VariableServiceConfigurator: IdentityLinkServiceConfiguration
// is rebuilt from scratch during buildEngine(), so pre-setting a custom IdentityLinkDataManager on
// the engine config beforehand would get silently overwritten.
public class IdentityLinkServiceConfigurator implements ServiceConfigurator<IdentityLinkServiceConfiguration> {

    @Override
    public void beforeInit(IdentityLinkServiceConfiguration identityLinkServiceConfiguration) {
        identityLinkServiceConfiguration.setIdentityLinkDataManager(new IdentityLinkDataManagerImpl());
    }

    @Override
    public void afterInit(IdentityLinkServiceConfiguration identityLinkServiceConfiguration) {
        // nothing needed after init.
    }
}
