package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.impl.ModelQueryImpl;
import org.flowable.engine.impl.persistence.entity.ModelEntity;
import org.flowable.engine.impl.persistence.entity.ModelEntityImpl;
import org.flowable.engine.impl.persistence.entity.data.ModelDataManager;
import org.flowable.engine.repository.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Discovered live, not anticipated (the workflow persistence layer): earlier bytecode
// analysis classified Model as genuinely unreachable, gated behind the modeler-application flags
// this app never sets -- true for every Model CRUD path, but not for deployment deletion.
// DeploymentEntityManagerImpl.deleteDeployment() unconditionally calls updateRelatedModels(), which
// queries ModelQuery.list() regardless of whether modeling is enabled, exactly like
// BpmnDeployer.createLocalizationValues()'s unconditional ACT_PROCDEF_INFO lookup turned out to be.
// Without this, the very first repositoryService.deleteDeployment() call hard-fails on
// ACT_RE_MODEL not existing. Nothing in this app ever creates a Model (confirmed via repo-wide grep,
// same as the event registry/IDM), so every write path here is unreachable in practice -- this
// exists purely to answer "no models" correctly for the one read path that's actually load-bearing.
public class ModelDataManagerImpl extends AbstractProcessDataManager implements ModelDataManager {

    @Override
    public ModelEntity create() {
        return new ModelEntityImpl();
    }

    @Override
    public ModelEntity findById(String id) {
        return null;
    }

    @Override
    public void insert(ModelEntity entity) {
        throw new UnsupportedOperationException("Models are never created in this app");
    }

    @Override
    public ModelEntity update(ModelEntity entity) {
        throw new UnsupportedOperationException("Models are never created in this app");
    }

    @Override
    public void delete(String id) {
        // no-op: nothing to delete, see class comment.
    }

    @Override
    public void delete(ModelEntity entity) {
        // no-op: nothing to delete, see class comment.
    }

    // The actual load-bearing method -- deleteDeployment()'s updateRelatedModels() calls this
    // unconditionally for every deployment being deleted.
    @Override
    public List<Model> findModelsByQueryCriteria(ModelQueryImpl modelQuery) {
        return new ArrayList<>();
    }

    @Override
    public long findModelCountByQueryCriteria(ModelQueryImpl modelQuery) {
        return 0;
    }

    @Override
    public List<Model> findModelsByNativeQuery(Map<String, Object> parameterMap) {
        return new ArrayList<>();
    }

    @Override
    public long findModelCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }
}
