package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.job.api.Job;
import org.flowable.job.service.impl.SuspendedJobQueryImpl;
import org.flowable.job.service.impl.persistence.entity.SuspendedJobEntity;
import org.flowable.job.service.impl.persistence.entity.SuspendedJobEntityImpl;
import org.flowable.job.service.impl.persistence.entity.data.SuspendedJobDataManager;

import java.util.ArrayList;
import java.util.List;

// Backs suspended jobs (ACT_RU_SUSPENDED_JOB) with job_kind = 'SUSPENDED' rows in process_job.
// SuspendedJobEntity has no lock fields at the interface level (a job is delocked before being
// suspended) -- AbstractJobDataManager's default no-op lock hooks cover that; nothing else to
// override beyond the entity-factory hooks and the stubbed query-object surface.
public class SuspendedJobDataManagerImpl extends AbstractJobDataManager<SuspendedJobEntity> implements SuspendedJobDataManager {

    @Override
    protected String jobKind() {
        return "SUSPENDED";
    }

    @Override
    protected SuspendedJobEntity newEntity() {
        return new SuspendedJobEntityImpl();
    }

    @Override
    protected Class<SuspendedJobEntity> entityType() {
        return SuspendedJobEntity.class;
    }

    @Override
    public List<Job> findJobsByQueryCriteria(SuspendedJobQueryImpl jobQuery) {
        return new ArrayList<>();
    }

    @Override
    public long findJobCountByQueryCriteria(SuspendedJobQueryImpl jobQuery) {
        return 0;
    }
}
