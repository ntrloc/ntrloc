package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.ServiceConfigurator;
import org.flowable.variable.service.VariableServiceConfiguration;

// VariableServiceConfiguration is shared infrastructure (used by BPMN/CMMN/DMN alike) that
// ProcessEngineConfigurationImpl always recreates from scratch during buildEngine() -- pre-setting
// a custom instance on the engine config beforehand gets silently overwritten. beforeInit() is the
// officially supported hook that runs before VariableServiceConfiguration.initDataManagers() (which
// only creates its default MyBatis-backed manager if the field is still null), so setting our
// custom one here is what actually sticks.
public class VariableServiceConfigurator implements ServiceConfigurator<VariableServiceConfiguration> {

    @Override
    public void beforeInit(VariableServiceConfiguration variableServiceConfiguration) {
        variableServiceConfiguration.setVariableInstanceDataManager(new VariableInstanceDataManagerImpl());
    }

    @Override
    public void afterInit(VariableServiceConfiguration variableServiceConfiguration) {
        // nothing needed after init.
    }
}
