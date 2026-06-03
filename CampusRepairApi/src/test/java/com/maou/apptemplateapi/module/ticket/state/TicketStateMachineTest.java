package com.maou.apptemplateapi.module.ticket.state;

import com.maou.apptemplateapi.module.ticket.enums.TicketAction;
import com.maou.apptemplateapi.module.ticket.enums.TicketStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TicketStateMachineTest {

    @Test
    void shouldAllowConfiguredBusinessTransitions() {
        assertThat(TicketStateMachine.canTransition(null, TicketStatus.PENDING_REVIEW, TicketAction.CREATE)).isTrue();
        assertThat(TicketStateMachine.canTransition(TicketStatus.PENDING_REVIEW, TicketStatus.ASSIGNED, TicketAction.ASSIGN)).isTrue();
        assertThat(TicketStateMachine.canTransition(TicketStatus.PENDING_REVIEW, TicketStatus.REJECTED, TicketAction.REJECT)).isTrue();
        assertThat(TicketStateMachine.canTransition(TicketStatus.ASSIGNED, TicketStatus.PROCESSING, TicketAction.ACCEPT)).isTrue();
        assertThat(TicketStateMachine.canTransition(TicketStatus.ASSIGNED, TicketStatus.RETURNED, TicketAction.WORKER_RETURN)).isTrue();
        assertThat(TicketStateMachine.canTransition(TicketStatus.PROCESSING, TicketStatus.WAITING_CONFIRM, TicketAction.SUBMIT_RESULT)).isTrue();
        assertThat(TicketStateMachine.canTransition(TicketStatus.WAITING_CONFIRM, TicketStatus.COMPLETED, TicketAction.EVALUATE)).isTrue();
        assertThat(TicketStateMachine.canTransition(TicketStatus.WAITING_CONFIRM, TicketStatus.PROCESSING, TicketAction.REQUEST_REWORK)).isTrue();
        assertThat(TicketStateMachine.canTransition(TicketStatus.REJECTED, TicketStatus.PENDING_REVIEW, TicketAction.RESUBMIT)).isTrue();
        assertThat(TicketStateMachine.canTransition(TicketStatus.RETURNED, TicketStatus.ASSIGNED, TicketAction.ASSIGN)).isTrue();
    }

    @Test
    void shouldRejectUnconfiguredTransitionsEvenWhenTargetStatusLooksReasonable() {
        assertThat(TicketStateMachine.canTransition(TicketStatus.PENDING_REVIEW, TicketStatus.PROCESSING, TicketAction.ACCEPT)).isFalse();
        assertThat(TicketStateMachine.canTransition(TicketStatus.ASSIGNED, TicketStatus.COMPLETED, TicketAction.EVALUATE)).isFalse();
        assertThat(TicketStateMachine.canTransition(TicketStatus.PROCESSING, TicketStatus.COMPLETED, TicketAction.EVALUATE)).isFalse();
        assertThat(TicketStateMachine.canTransition(TicketStatus.RETURNED, TicketStatus.PROCESSING, TicketAction.ACCEPT)).isFalse();
        assertThat(TicketStateMachine.canTransition(TicketStatus.COMPLETED, TicketStatus.PROCESSING, TicketAction.REQUEST_REWORK)).isFalse();
    }

    @Test
    void shouldExposeUnifiedStatusGroups() {
        assertThat(TicketStateMachine.isUrgeable(TicketStatus.ASSIGNED)).isTrue();
        assertThat(TicketStateMachine.isUrgeable(TicketStatus.PROCESSING)).isTrue();
        assertThat(TicketStateMachine.isUrgeable(TicketStatus.WAITING_CONFIRM)).isTrue();
        assertThat(TicketStateMachine.isUrgeable(TicketStatus.RETURNED)).isFalse();

        assertThat(TicketStateMachine.isActive(TicketStatus.PENDING_REVIEW)).isTrue();
        assertThat(TicketStateMachine.isActive(TicketStatus.ASSIGNED)).isTrue();
        assertThat(TicketStateMachine.isActive(TicketStatus.PROCESSING)).isTrue();
        assertThat(TicketStateMachine.isActive(TicketStatus.WAITING_CONFIRM)).isTrue();
        assertThat(TicketStateMachine.isActive(TicketStatus.RETURNED)).isFalse();
        assertThat(TicketStateMachine.isActive(TicketStatus.COMPLETED)).isFalse();
        assertThat(TicketStateMachine.isActive(TicketStatus.REJECTED)).isFalse();

        assertThat(TicketStateMachine.activeStatusNames())
                .containsExactlyInAnyOrder("PENDING_REVIEW", "ASSIGNED", "PROCESSING", "WAITING_CONFIRM");
    }
}
