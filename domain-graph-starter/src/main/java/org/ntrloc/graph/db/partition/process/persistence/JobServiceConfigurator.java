package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.ServiceConfigurator;
import org.flowable.job.service.JobServiceConfiguration;

// Same beforeInit()-hook reasoning as VariableServiceConfigurator/TaskServiceConfigurator:
// JobServiceConfiguration is shared cross-engine infrastructure rebuilt from scratch during
// buildEngine(), so pre-setting a custom DataManager on the engine config beforehand would get
// silently overwritten.
public class JobServiceConfigurator implements ServiceConfigurator<JobServiceConfiguration> {

    @Override
    public void beforeInit(JobServiceConfiguration jobServiceConfiguration) {
        jobServiceConfiguration.setJobDataManager(new JobDataManagerImpl());
        jobServiceConfiguration.setTimerJobDataManager(new TimerJobDataManagerImpl());
        jobServiceConfiguration.setSuspendedJobDataManager(new SuspendedJobDataManagerImpl());
        jobServiceConfiguration.setDeadLetterJobDataManager(new DeadLetterJobDataManagerImpl());
        jobServiceConfiguration.setHistoryJobDataManager(new HistoryJobDataManagerImpl());
        jobServiceConfiguration.setExternalWorkerJobDataManager(new ExternalWorkerJobDataManagerImpl());
    }

    @Override
    public void afterInit(JobServiceConfiguration jobServiceConfiguration) {
        // nothing needed after init.
    }
}
