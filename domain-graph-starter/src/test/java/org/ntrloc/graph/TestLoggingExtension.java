package org.ntrloc.graph;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestLoggingExtension  implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Logger logger = LoggerFactory.getLogger(TestLoggingExtension.class);

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        logger.info("STARTING TEST: {}.{}",
                context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        logger.info("COMPLETED TEST: {}.{}",
                context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName());
    }

}
