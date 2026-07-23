package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.job.api.Job;
import org.flowable.job.service.impl.DeadLetterJobQueryImpl;
import org.flowable.job.service.impl.persistence.entity.DeadLetterJobEntity;
import org.flowable.job.service.impl.persistence.entity.DeadLetterJobEntityImpl;
import org.flowable.job.service.impl.persistence.entity.data.DeadLetterJobDataManager;

import java.util.ArrayList;
import java.util.List;

// Backs dead-letter jobs (ACT_RU_DEADLETTER_JOB) with job_kind = 'DEADLETTER' rows in process_job
// -- where a job lands once JobRetryCmd exhausts its retries (docs/ntrloc-workflow-summary.md
// Section 6). See SuspendedJobDataManagerImpl's class comment; same shape.
public class DeadLetterJobDataManagerImpl extends AbstractJobDataManager<DeadLetterJobEntity> implements DeadLetterJobDataManager {

    @Override
    protected String jobKind() {
        return "DEADLETTER";
    }

    @Override
    protected DeadLetterJobEntity newEntity() {
        return new DeadLetterJobEntityImpl();
    }

    @Override
    protected Class<DeadLetterJobEntity> entityType() {
        return DeadLetterJobEntity.class;
    }

    @Override
    public List<Job> findJobsByQueryCriteria(DeadLetterJobQueryImpl jobQuery) {
        return new ArrayList<>();
    }

    @Override
    public long findJobCountByQueryCriteria(DeadLetterJobQueryImpl jobQuery) {
        return 0;
    }
}
