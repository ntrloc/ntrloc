package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.ServiceConfigurator;
import org.flowable.eventsubscription.service.EventSubscriptionServiceConfiguration;

// Same beforeInit()-hook reasoning as VariableServiceConfigurator/TaskServiceConfigurator/
// JobServiceConfigurator: EventSubscriptionServiceConfiguration is shared cross-engine
// infrastructure rebuilt from scratch during buildEngine().
public class EventSubscriptionServiceConfigurator implements ServiceConfigurator<EventSubscriptionServiceConfiguration> {

    @Override
    public void beforeInit(EventSubscriptionServiceConfiguration eventSubscriptionServiceConfiguration) {
        eventSubscriptionServiceConfiguration.setEventSubscriptionDataManager(new EventSubscriptionDataManagerImpl());
    }

    @Override
    public void afterInit(EventSubscriptionServiceConfiguration eventSubscriptionServiceConfiguration) {
        // nothing needed after init.
    }
}
