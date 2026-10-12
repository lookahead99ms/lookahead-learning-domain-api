package com.lookahead.domain.access;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaffPermissionsTest {
    @Test void testerGetsCurriculumButNoAdministrativeAccess() {
        var p = StaffPermissions.forRoles(false, Set.of("tester"));
        assertTrue(p.fullCurriculum()); assertEquals("tester", p.feedbackRole());
        assertFalse(p.manageAccess()); assertFalse(p.previewDocuments()); assertFalse(p.deliveryRead());
        assertFalse(p.triageFeedback()); assertFalse(p.permitsDeliveryWrite(false, "review", "done"));
    }
    @Test void adminCanPrepareButCannotSignOffOrAssignRoles() {
        var p = StaffPermissions.forRoles(false, Set.of("admin"));
        assertTrue(p.triageFeedback()); assertTrue(p.previewDocuments()); assertTrue(p.deliveryRead());
        assertTrue(p.permitsDeliveryWrite(true, null, "backlog"));
        assertTrue(p.permitsDeliveryWrite(false, "in-progress", "review"));
        assertFalse(p.permitsDeliveryWrite(true, null, "done"));
        assertFalse(p.permitsDeliveryWrite(false, "review", "done"));
        assertFalse(p.permitsDeliveryWrite(false, "done", "review"));
        assertFalse(p.manageAccess()); assertFalse(p.fullCurriculum());
    }
    @Test void reviewerCanSignOffOnlyAStoryAlreadyInReview() {
        var p = StaffPermissions.forRoles(false, Set.of("reviewer"));
        assertTrue(p.permitsDeliveryWrite(false, "review", "done"));
        assertFalse(p.permitsDeliveryWrite(false, "backlog", "done"));
        assertFalse(p.permitsDeliveryWrite(true, null, "backlog"));
        assertFalse(p.manageAccess()); assertFalse(p.triageFeedback());
    }
    @Test void ownerRetainsFullControlAndRolesComposeWithoutConferringOwnership() {
        var p = StaffPermissions.forRoles(true, Set.of());
        assertTrue(p.manageAccess()); assertTrue(p.permitsDeliveryWrite(false, "done", "backlog"));
        var combined = StaffPermissions.forRoles(false, Set.of("admin", "reviewer", "tester"));
        assertTrue(combined.deliveryDone()); assertTrue(combined.fullCurriculum()); assertFalse(combined.manageAccess());
        assertThrows(IllegalArgumentException.class, () -> StaffPermissions.forRoles(false, Set.of("owner")));
    }
    @Test void learnerCannotUseAdministrativePermissions() {
        var p = StaffPermissions.forRoles(false, Set.of());
        assertEquals("user", p.feedbackRole()); assertFalse(p.fullCurriculum());
        assertFalse(p.previewDocuments()); assertFalse(p.deliveryRead()); assertFalse(p.triageFeedback());
        assertFalse(p.permitsDeliveryWrite(true, null, "backlog"));
    }
}
