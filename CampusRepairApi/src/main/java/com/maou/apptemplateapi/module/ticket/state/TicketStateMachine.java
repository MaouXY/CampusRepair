package com.maou.apptemplateapi.module.ticket.state;

import com.maou.apptemplateapi.module.ticket.enums.TicketAction;
import com.maou.apptemplateapi.module.ticket.enums.TicketStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class TicketStateMachine {

    private static final Set<TicketStatus> ACTIVE_STATUSES = EnumSet.of(
            TicketStatus.PENDING_REVIEW,
            TicketStatus.ASSIGNED,
            TicketStatus.PROCESSING,
            TicketStatus.WAITING_CONFIRM
    );
    private static final Set<TicketStatus> TERMINAL_STATUSES = EnumSet.of(
            TicketStatus.COMPLETED,
            TicketStatus.REJECTED
    );
    private static final Set<TicketStatus> URGEABLE_STATUSES = EnumSet.of(
            TicketStatus.ASSIGNED,
            TicketStatus.PROCESSING,
            TicketStatus.WAITING_CONFIRM
    );
    private static final Map<TicketAction, TransitionRule> TRANSITIONS = buildTransitions();

    private TicketStateMachine() {
    }

    public static TicketStatus parse(String status) {
        if (status == null) {
            throw new IllegalArgumentException("ticket status must not be null");
        }
        return TicketStatus.valueOf(status);
    }

    public static boolean canTransition(TicketStatus fromStatus, TicketStatus toStatus, TicketAction action) {
        if (fromStatus == null) {
            return action == TicketAction.CREATE && toStatus == TicketStatus.PENDING_REVIEW;
        }
        TransitionRule rule = TRANSITIONS.get(action);
        return rule != null && rule.fromStatuses().contains(fromStatus) && rule.toStatus() == toStatus;
    }

    public static Set<TicketStatus> allowedFromStatuses(TicketAction action) {
        TransitionRule rule = TRANSITIONS.get(action);
        return rule == null ? Set.of() : Set.copyOf(rule.fromStatuses());
    }

    public static boolean isUrgeable(TicketStatus status) {
        return URGEABLE_STATUSES.contains(status);
    }

    public static boolean isActive(TicketStatus status) {
        return ACTIVE_STATUSES.contains(status);
    }

    public static boolean isTerminal(TicketStatus status) {
        return TERMINAL_STATUSES.contains(status);
    }

    public static Set<String> activeStatusNames() {
        return statusNames(ACTIVE_STATUSES);
    }

    private static Map<TicketAction, TransitionRule> buildTransitions() {
        Map<TicketAction, TransitionRule> transitions = new EnumMap<>(TicketAction.class);
        transitions.put(TicketAction.ASSIGN, new TransitionRule(
                EnumSet.of(TicketStatus.PENDING_REVIEW, TicketStatus.RETURNED),
                TicketStatus.ASSIGNED
        ));
        transitions.put(TicketAction.REJECT, new TransitionRule(
                EnumSet.of(TicketStatus.PENDING_REVIEW, TicketStatus.RETURNED),
                TicketStatus.REJECTED
        ));
        transitions.put(TicketAction.ACCEPT, new TransitionRule(
                EnumSet.of(TicketStatus.ASSIGNED),
                TicketStatus.PROCESSING
        ));
        transitions.put(TicketAction.WORKER_RETURN, new TransitionRule(
                EnumSet.of(TicketStatus.ASSIGNED),
                TicketStatus.RETURNED
        ));
        transitions.put(TicketAction.SUBMIT_RESULT, new TransitionRule(
                EnumSet.of(TicketStatus.PROCESSING),
                TicketStatus.WAITING_CONFIRM
        ));
        transitions.put(TicketAction.EVALUATE, new TransitionRule(
                EnumSet.of(TicketStatus.WAITING_CONFIRM),
                TicketStatus.COMPLETED
        ));
        transitions.put(TicketAction.REQUEST_REWORK, new TransitionRule(
                EnumSet.of(TicketStatus.WAITING_CONFIRM),
                TicketStatus.PROCESSING
        ));
        transitions.put(TicketAction.RESUBMIT, new TransitionRule(
                EnumSet.of(TicketStatus.REJECTED),
                TicketStatus.PENDING_REVIEW
        ));
        return Map.copyOf(transitions);
    }

    private static Set<String> statusNames(Set<TicketStatus> statuses) {
        return statuses.stream().map(TicketStatus::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record TransitionRule(Set<TicketStatus> fromStatuses, TicketStatus toStatus) {
    }
}
