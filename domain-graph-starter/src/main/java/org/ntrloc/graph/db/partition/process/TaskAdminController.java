package org.ntrloc.graph.db.partition.process;

import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/process")
// java.util.Date is unavoidable here: TaskView.createTime mirrors Flowable's own Task.getCreateTime(),
// which predates java.time.
@SuppressWarnings("java:S2143")
public class TaskAdminController {

    public record TaskView(String id, String name, String processInstanceId, AssigneeView assignee, Date createTime) {}

    // id is Flowable's stored assignee (a security_user.id string); displayName/email are null if it
    // no longer resolves to a user.
    public record AssigneeView(String id, String displayName, String email) {}

    public record ProcessGroupView(String name) {}

    public record TaskErrorResponse(String message) {}

    private final TaskService taskService;
    private final ProcessGroupRepository groupRepo;
    private final ExternalPrincipalResolver principalResolver;
    private final TaskViewAssembler taskViews;
    private final TaskEventBroadcaster broadcaster;

    public TaskAdminController(TaskService taskService, ProcessGroupRepository groupRepo,
            ExternalPrincipalResolver principalResolver, TaskViewAssembler taskViews,
            TaskEventBroadcaster broadcaster) {
        this.taskService = taskService;
        this.groupRepo = groupRepo;
        this.principalResolver = principalResolver;
        this.taskViews = taskViews;
        this.broadcaster = broadcaster;
    }

    // A trivial "something changed" signal, not a data channel -- every connected client just
    // re-fetches GET /tasks (see TaskEventBroadcaster for why), so this needs no per-principal
    // filtering of its own.
    @GetMapping(value = "/tasks/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter taskEvents() {
        return broadcaster.register();
    }

    // Admins see every task (useful with only a couple of seed accounts to test with);
    // everyone else sees only tasks assigned to them or where they're a candidate (by user id
    // directly, or via a process_group they belong to -- see ProcessGroupRepository, deliberately
    // not Flowable's own unwired IdentityService).
    @GetMapping("/tasks")
    List<TaskView> listTasks(Authentication authentication) {
        NtrlocPrincipal principal = principalResolver.resolve(authentication);
        var query = taskService.createTaskQuery();
        if (!principal.isAdmin()) {
            Set<String> groupNames = groupRepo.getGroupNamesForUser(principal.id());
            var or = query.or()
                    .taskAssignee(principal.id().toString())
                    .taskCandidateUser(principal.id().toString());
            if (!groupNames.isEmpty()) {
                or = or.taskCandidateGroupIn(List.copyOf(groupNames));
            }
            or.endOr();
        }
        return taskViews.toViews(query.list());
    }

    @PostMapping("/tasks/{id}/claim")
    ResponseEntity<?> claim(@PathVariable String id, Authentication authentication) {
        NtrlocPrincipal principal = principalResolver.resolve(authentication);
        try {
            taskService.claim(id, principal.id().toString());
            return ResponseEntity.ok(taskViews.toView(taskService.createTaskQuery().taskId(id).singleResult()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TaskErrorResponse("Failed to claim task: " + e.getMessage()));
        }
    }

    @PostMapping("/tasks/{id}/complete")
    ResponseEntity<?> complete(@PathVariable String id, @RequestBody(required = false) Map<String, Object> variables) {
        try {
            taskService.complete(id, variables == null ? Map.of() : variables);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TaskErrorResponse("Failed to complete task: " + e.getMessage()));
        }
    }

    @GetMapping("/groups")
    List<ProcessGroupView> listGroups() {
        return groupRepo.listGroups().stream()
                .map(g -> new ProcessGroupView(g.name()))
                .toList();
    }
}
