package com.maou.apptemplateapi.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "success"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "resource not found"),
    CONFLICT(409, "business conflict"),
    TOO_MANY_REQUESTS(429, "too many requests"),
    INTERNAL_ERROR(500, "internal server error"),

    AUTH_LOGIN_FAILED(9001, "username or password is incorrect"),
    AUTH_ACCOUNT_DISABLED(9002, "account is disabled"),
    AUTH_CURRENT_USER_NOT_FOUND(9003, "current user not found"),
    AUTH_ROLE_FORBIDDEN(9004, "role is not allowed"),
    AUTH_TOKEN_INVALID_OR_EXPIRED(9005, "token is invalid or expired"),

    TICKET_NOT_FOUND(9601, "repair ticket not found"),
    TICKET_STATUS_INVALID(9602, "repair ticket status invalid"),
    TICKET_ACCESS_DENIED(9603, "repair ticket access denied"),
    TICKET_WORKER_INVALID(9604, "repair worker invalid"),
    TICKET_EVALUATION_DUPLICATED(9605, "repair ticket evaluation duplicated"),
    TICKET_BASE_DATA_INVALID(9606, "repair base data invalid"),
    MANAGEMENT_DATA_NOT_FOUND(9701, "management data not found"),
    MANAGEMENT_DATA_INVALID(9702, "management data invalid"),
    MANAGEMENT_DATA_DUPLICATED(9703, "management data duplicated"),

    CHILD_NOT_FOUND(9101, "child profile not found"),
    CHILD_CODE_DUPLICATED(9102, "child code duplicated"),
    CHILD_SENSITIVE_CONFIRM_REQUIRED(9103, "sensitive view confirmation required"),
    CHILD_SENSITIVE_AUDIT_REQUIRED(9104, "sensitive audit record required"),

    CASE_NOT_FOUND(9201, "case not found"),
    CASE_STATUS_INVALID(9202, "case status invalid"),

    VISIT_NOT_FOUND(9301, "visit not found"),
    VISIT_STATUS_INVALID(9302, "visit status invalid"),
    VISIT_WORKER_INVALID(9303, "visit worker invalid"),
    VISIT_OBSERVATION_STATUS_INVALID(9304, "visit observation status invalid"),

    DOCUMENT_NOT_FOUND(9401, "document not found"),
    DOCUMENT_STATUS_INVALID(9402, "document status invalid"),

    SERVICE_PLAN_NOT_FOUND(9501, "service plan not found"),
    SERVICE_PLAN_STATUS_INVALID(9502, "service plan status invalid"),

    TASK_NOT_FOUND(10001, "task not found"),
    TASK_NOT_AVAILABLE(10002, "task is not available"),
    TASK_DUPLICATED_SUBMISSION(10003, "task has already been submitted"),
    TASK_STATUS_INVALID(10004, "task status invalid"),
    TASK_TEMPLATE_NOT_FOUND(10005, "task template not found"),
    TASK_TEMPLATE_STATUS_INVALID(10006, "task template status invalid"),

    TASK_CLAIM_NOT_FOUND(10501, "task claim not found"),
    TASK_ALREADY_CLAIMED(10502, "task already claimed"),
    TASK_CLAIM_STATUS_INVALID(10503, "task claim status invalid"),

    SUBMISSION_NOT_FOUND(11001, "submission not found"),
    SUBMISSION_STATUS_INVALID(11002, "submission status invalid"),
    HELP_REQUEST_NOT_FOUND(11501, "help request not found"),
    HELP_REQUEST_STATUS_INVALID(11502, "help request status invalid"),

    FILE_NOT_FOUND(12001, "file not found"),
    FILE_TYPE_INVALID(12002, "file type invalid"),
    FILE_SIZE_EXCEEDED(12003, "file size exceeded"),
    FILE_ALREADY_BOUND(12004, "file already bound"),
    FILE_ACCESS_DENIED(12005, "file access denied"),
    FILE_DELETE_NOT_ALLOWED(12006, "file delete not allowed"),
    FILE_UPLOAD_FAILED(12007, "file upload failed"),
    FILE_DOWNLOAD_FAILED(12008, "file download failed"),
    FILE_BIND_INVALID(12009, "file binding invalid"),

    GROWTH_REWARD_DUPLICATED(20001, "growth reward duplicated"),
    GROWTH_POINTS_NOT_ENOUGH(20002, "growth points not enough"),

    AI_SERVICE_UNAVAILABLE(30001, "AI service unavailable"),
    AI_OUTPUT_BLOCKED(30002, "AI output blocked"),
    AI_PROMPT_TEMPLATE_NOT_FOUND(30003, "AI prompt template not found"),
    SERVER_ASR_DISABLED(30004, "server ASR disabled"),
    AI_SETTINGS_INVALID(30005, "AI settings invalid"),
    AGENT_WORKBENCH_NOT_FOUND(30011, "agent workbench snapshot not found"),
    AGENT_ACTION_SUGGESTION_NOT_FOUND(30012, "agent action suggestion not found"),
    AGENT_ACTION_STATUS_INVALID(30013, "agent action status invalid"),
    AGENT_OUTPUT_INVALID(30014, "agent output invalid"),
    CHILD_ACTIVITY_TYPE_INVALID(30101, "child activity type invalid"),
    CHILD_ACTIVITY_NOT_FOUND(30102, "child activity not found"),
    CHILD_ACTIVITY_STATUS_INVALID(30103, "child activity status invalid"),
    CHILD_ACTIVITY_SUBMISSION_INVALID(30104, "child activity submission invalid"),
    CHILD_ACTIVITY_FEEDBACK_NOT_FOUND(30105, "child activity feedback not found"),
    CHILD_ACTIVITY_PREFERENCE_NOT_FOUND(30106, "child activity preference not found"),
    CHILD_ACTIVITY_PREFERENCE_INVALID(30107, "child activity preference invalid"),
    CHILD_ACTIVITY_AI_GENERATE_FAILED(30108, "child activity AI generation failed"),

    RISK_CONTENT_BLOCKED(40001, "risk content blocked"),
    RISK_RECORD_NOT_FOUND(40002, "risk record not found"),
    RISK_RECORD_STATUS_INVALID(40003, "risk record status invalid"),
    RISK_KEYWORD_NOT_FOUND(40004, "risk keyword not found"),
    RISK_KEYWORD_DUPLICATED(40005, "risk keyword duplicated");

    private final int code;
    private final String message;
}

