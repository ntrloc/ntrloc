package org.ntrloc.graph.ai;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.ntrloc.graph.db.partition.process.ProcessAdminController;
import org.ntrloc.graph.db.partition.process.TaskAdminController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ProcessService {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessService.class);

    // Same reasoning as ProjectionService.MCP_PRINCIPAL: an MCP tool call has no real per-caller
    // identity threaded through it the way a REST request does (TaskAdminController's principal-
    // based filtering needs both a ServerHttpRequest and an Authentication, resolved as controller-
    // method parameters). listTasks below deliberately returns every task unfiltered rather than
    // pretending to filter by a caller identity that doesn't exist yet -- claimTask/completeTask
    // still take an explicit assignee/userId parameter, matching Flowable's own
    // TaskService.claim()/complete() signatures, so the caller (or the agent driving this tool)
    // says who they're acting as rather than this service guessing.
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    public ProcessService(RepositoryService repositoryService, RuntimeService runtimeService, TaskService taskService) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    @McpTool(description = """
            Lists deployed BPMN process definitions: id, key, name, version, and deployment id.

            The id (not the key) is what runProcess needs -- Flowable's own id format encodes key,
            version, and a generated suffix (e.g. "helloWorld:1:1"), and multiple versions of the
            same key can be deployed at once.
            """)
    public List<ProcessAdminController.ProcessDefinitionView> listProcesses() {
        try {
            LOG.info("Listing process definitions");
            return repositoryService.createProcessDefinitionQuery().list().stream()
                    .map(ProcessService::toDefinitionView)
                    .toList();
        } catch (Exception e) {
            LOG.error("Error while listing process definitions", e);
            throw new RuntimeException("Error while listing process definitions: " + e.getMessage(), e);
        }
    }

    @McpTool(description = """
            Starts a new instance of a deployed process definition, returning its instance id and
            whether it already ran to completion synchronously (ended=true) or is now paused at a
            User Task (ended=false, see listTasks).
            """)
    public ProcessAdminController.ProcessInstanceView runProcess(@McpToolParam(description = """
            The process definition id, exactly as returned by listProcesses (not just the key).
            """) String definitionId) {
        try {
            LOG.info("Starting process instance for definition {}", definitionId);
            ProcessInstance instance = runtimeService.startProcessInstanceById(definitionId);
            return new ProcessAdminController.ProcessInstanceView(instance.getId(), instance.isEnded());
        } catch (Exception e) {
            LOG.error("Error while starting process instance", e);
            throw new RuntimeException("Error while starting process instance: " + e.getMessage(), e);
        }
    }

    @McpTool(description = """
            Lists every runtime User Task currently awaiting action, across all process instances:
            id, name, process instance id, current assignee (null if unclaimed), and created time.
            """)
    public List<TaskAdminController.TaskView> listTasks() {
        try {
            LOG.info("Listing tasks");
            return taskService.createTaskQuery().list().stream()
                    .map(ProcessService::toTaskView)
                    .toList();
        } catch (Exception e) {
            LOG.error("Error while listing tasks", e);
            throw new RuntimeException("Error while listing tasks: " + e.getMessage(), e);
        }
    }

    @McpTool(description = """
            Claims a task for a specific user, making them its assignee so they (and only they)
            can complete it. Returns the task's updated state.
            """)
    public TaskAdminController.TaskView claimTask(
            @McpToolParam(description = "The task id, exactly as returned by listTasks.") String taskId,
            @McpToolParam(description = "The external id (username) of the user claiming the task.") String assignee) {
        try {
            LOG.info("Claiming task {} for {}", taskId, assignee);
            taskService.claim(taskId, assignee);
            return toTaskView(taskService.createTaskQuery().taskId(taskId).singleResult());
        } catch (Exception e) {
            LOG.error("Error while claiming task", e);
            throw new RuntimeException("Error while claiming task: " + e.getMessage(), e);
        }
    }

    @McpTool(description = """
            Completes a task, advancing its process instance past it (which may immediately reach
            the end of the process, or pause again at a further User Task). Optional variables are
            set on the process instance as part of completing.
            """)
    public String completeTask(
            @McpToolParam(description = "The task id, exactly as returned by listTasks.") String taskId,
            @McpToolParam(description = "Optional process variables to set on completion.", required = false) Map<String, Object> variables) {
        try {
            LOG.info("Completing task {}", taskId);
            taskService.complete(taskId, variables == null ? Map.of() : variables);
            return "Task " + taskId + " completed.";
        } catch (Exception e) {
            LOG.error("Error while completing task", e);
            throw new RuntimeException("Error while completing task: " + e.getMessage(), e);
        }
    }

    private static ProcessAdminController.ProcessDefinitionView toDefinitionView(ProcessDefinition definition) {
        return new ProcessAdminController.ProcessDefinitionView(
                definition.getId(), definition.getKey(), definition.getName(),
                definition.getVersion(), definition.getDeploymentId());
    }

    private static TaskAdminController.TaskView toTaskView(Task task) {
        return new TaskAdminController.TaskView(
                task.getId(), task.getName(), task.getProcessInstanceId(), task.getAssignee(), task.getCreateTime());
    }
}
