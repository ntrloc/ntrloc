package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.ServiceConfigurator;
import org.flowable.task.service.TaskServiceConfiguration;

// Same beforeInit()-hook reasoning as VariableServiceConfigurator: TaskServiceConfiguration is
// rebuilt from scratch during buildEngine(), so pre-setting a custom TaskDataManager on the engine
// config beforehand would get silently overwritten.
public class TaskServiceConfigurator implements ServiceConfigurator<TaskServiceConfiguration> {

    @Override
    public void beforeInit(TaskServiceConfiguration taskServiceConfiguration) {
        taskServiceConfiguration.setTaskDataManager(new TaskDataManagerImpl());
    }

    @Override
    public void afterInit(TaskServiceConfiguration taskServiceConfiguration) {
        // nothing needed after init.
    }
}
