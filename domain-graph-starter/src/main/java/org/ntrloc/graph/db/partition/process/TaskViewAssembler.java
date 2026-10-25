package org.ntrloc.graph.db.partition.process;

import org.flowable.task.api.Task;
import org.ntrloc.graph.db.partition.process.TaskAdminController.AssigneeView;
import org.ntrloc.graph.db.partition.process.TaskAdminController.TaskView;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// Builds TaskViews for both the admin REST API and the MCP process tools. A Flowable task's
// assignee is a security_user.id string; it's resolved here to the user's current display name and
// email, in one batched lookup for the whole list rather than one query per task.
@Component
public class TaskViewAssembler {

    private final SecurityRepository securityRepository;

    public TaskViewAssembler(SecurityRepository securityRepository) {
        this.securityRepository = securityRepository;
    }

    public TaskView toView(Task task) {
        return toViews(List.of(task)).getFirst();
    }

    public List<TaskView> toViews(List<Task> tasks) {
        Set<UUID> assigneeIds = new HashSet<>();
        tasks.forEach(task -> parseUserId(task.getAssignee()).ifPresent(assigneeIds::add));
        Map<UUID, SecurityRepository.UserRow> users = securityRepository.findUsersByIds(assigneeIds).stream()
                .collect(Collectors.toMap(SecurityRepository.UserRow::id, Function.identity()));
        return tasks.stream()
                .map(task -> new TaskView(task.getId(), task.getName(), task.getProcessInstanceId(),
                        toAssigneeView(task.getAssignee(), users), task.getCreateTime()))
                .toList();
    }

    private static AssigneeView toAssigneeView(String assignee, Map<UUID, SecurityRepository.UserRow> users) {
        if (assignee == null) {
            return null;
        }
        return parseUserId(assignee).map(users::get)
                .map(user -> new AssigneeView(assignee, user.displayName(), user.email()))
                .orElse(new AssigneeView(assignee, null, null));
    }

    static Optional<UUID> parseUserId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
