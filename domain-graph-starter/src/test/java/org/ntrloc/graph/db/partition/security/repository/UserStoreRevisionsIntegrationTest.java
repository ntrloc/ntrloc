package org.ntrloc.graph.db.partition.security.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Which users and groups each write bumps (docs/ntrloc-system-model-notes.md §13.3): exactly the ones it
// changes, so a draft that depends on one can tell, and nothing else.
class UserStoreRevisionsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserStore userStore;

    private UserStore.UserGroupRow group(String name) {
        return userStore.createUserGroup(name + "-" + UUID.randomUUID());
    }

    private UserStore.UserRow user() {
        return userStore.createUser("User", "user-" + UUID.randomUUID() + "@example.com", false);
    }

    // Each group's and user's revision now, to compare after a write.
    private record Revisions(Map<UUID, Long> groups, Map<UUID, Long> users) {

        // Which of them changed since.
        List<UUID> bumpedSince(Revisions before) {
            var bumped = new java.util.ArrayList<UUID>();
            before.groups.forEach((id, revision) -> { if (!revision.equals(groups.get(id))) bumped.add(id); });
            before.users.forEach((id, revision) -> { if (!revision.equals(users.get(id))) bumped.add(id); });
            return bumped;
        }
    }

    private Revisions revisions(List<UUID> groups, List<UUID> users) {
        return new Revisions(userStore.groupRevisions(groups), userStore.userRevisions(users));
    }

    @Test
    void renamingAGroup_bumpsOnlyThatGroup() {
        var parent = group("parent");
        var renamed = userStore.createUserGroup("renamed-" + UUID.randomUUID(), parent.id());
        var before = revisions(List.of(parent.id(), renamed.id()), List.of());

        userStore.updateUserGroup(renamed.id(), "new-name-" + UUID.randomUUID());

        assertThat(revisions(List.of(parent.id(), renamed.id()), List.of()).bumpedSince(before)).containsExactly(renamed.id());
    }

    @Test
    void movingAGroup_bumpsIt_itsOldParent_andItsNewParent_butNotWhatsAboveThem() {
        var top = group("top");
        var oldParent = userStore.createUserGroup("old-" + UUID.randomUUID(), top.id());
        var newParent = userStore.createUserGroup("new-" + UUID.randomUUID(), top.id());
        var moved = userStore.createUserGroup("moved-" + UUID.randomUUID(), oldParent.id());
        var all = List.of(top.id(), oldParent.id(), newParent.id(), moved.id());
        var before = revisions(all, List.of());

        userStore.setParent(moved.id(), newParent.id());

        assertThat(revisions(all, List.of()).bumpedSince(before)).containsExactlyInAnyOrder(moved.id(), oldParent.id(), newParent.id());
    }

    @Test
    void creatingAGroup_bumpsItsParent() {
        var parent = group("parent");
        var before = revisions(List.of(parent.id()), List.of());

        userStore.createUserGroup("child-" + UUID.randomUUID(), parent.id());

        assertThat(revisions(List.of(parent.id()), List.of()).bumpedSince(before)).containsExactly(parent.id());
    }

    @Test
    void addingAndRemovingAMember_bumpsTheUserAndTheGroup_eachTime() {
        var group = group("group");
        var other = group("other");
        var user = user();
        var ids = List.of(group.id(), other.id());

        var beforeAdd = revisions(ids, List.of(user.id()));
        userStore.addUserToUserGroup(user.id(), group.id());
        assertThat(revisions(ids, List.of(user.id())).bumpedSince(beforeAdd)).containsExactlyInAnyOrder(group.id(), user.id());

        var beforeRemove = revisions(ids, List.of(user.id()));
        userStore.removeUserFromUserGroup(user.id(), group.id());
        assertThat(revisions(ids, List.of(user.id())).bumpedSince(beforeRemove)).containsExactlyInAnyOrder(group.id(), user.id());
    }

    @Test
    void addingAMemberAlreadyThere_bumpsNothing() {
        var group = group("group");
        var user = user();
        userStore.addUserToUserGroup(user.id(), group.id());
        var before = revisions(List.of(group.id()), List.of(user.id()));

        userStore.addUserToUserGroup(user.id(), group.id());

        assertThat(revisions(List.of(group.id()), List.of(user.id())).bumpedSince(before)).isEmpty();
    }

    @Test
    void updatingAUser_bumpsTheUser() {
        var user = user();
        var before = revisions(List.of(), List.of(user.id()));

        userStore.updateUser(user.id(), "Renamed", user.email(), true);

        assertThat(revisions(List.of(), List.of(user.id())).bumpedSince(before)).containsExactly(user.id());
    }

    @Test
    void deletingAUser_bumpsTheirGroups() {
        var group = group("group");
        var other = group("other");
        var user = user();
        userStore.addUserToUserGroup(user.id(), group.id());
        var before = revisions(List.of(group.id(), other.id()), List.of());

        userStore.deleteUser(user.id());

        assertThat(revisions(List.of(group.id(), other.id()), List.of()).bumpedSince(before)).containsExactly(group.id());
    }

    @Test
    void deletingGroups_bumpsTheParentAndEveryMemberOfThem() {
        var parent = group("parent");
        var deleted = userStore.createUserGroup("deleted-" + UUID.randomUUID(), parent.id());
        var subgroup = userStore.createUserGroup("sub-" + UUID.randomUUID(), deleted.id());
        var member = user();
        var subgroupMember = user();
        var bystander = user();
        userStore.addUserToUserGroup(member.id(), deleted.id());
        userStore.addUserToUserGroup(subgroupMember.id(), subgroup.id());
        var users = List.of(member.id(), subgroupMember.id(), bystander.id());
        var before = revisions(List.of(parent.id()), users);

        userStore.deleteUserGroup(deleted.id(), true);

        var after = revisions(List.of(parent.id(), deleted.id(), subgroup.id()), users);
        assertThat(after.groups()).containsOnlyKeys(parent.id());
        assertThat(after.bumpedSince(before)).containsExactlyInAnyOrder(parent.id(), member.id(), subgroupMember.id());
    }

    @Test
    void addingEveryUserToAGroup_bumpsTheGroupAndThoseItAdded() {
        var group = group("group");
        var alreadyIn = user();
        userStore.addUserToUserGroup(alreadyIn.id(), group.id());
        var newcomer = user();
        var before = revisions(List.of(group.id()), List.of(alreadyIn.id(), newcomer.id()));

        userStore.addAllUsersToUserGroup(group.id());

        assertThat(revisions(List.of(group.id()), List.of(alreadyIn.id(), newcomer.id())).bumpedSince(before))
                .containsExactlyInAnyOrder(group.id(), newcomer.id());
    }
}
