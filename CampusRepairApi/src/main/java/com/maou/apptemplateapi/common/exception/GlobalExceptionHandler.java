package com.maou.apptemplateapi.common.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.maou.apptemplateapi.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@Hidden
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    @Hidden
    public ApiResponse<Void> handleBusinessException(BusinessException exception) {
        return ApiResponse.fail(exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @Hidden
    public ApiResponse<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @Hidden
    public ApiResponse<Void> handleBindException(BindException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @Hidden
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException exception) {
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @Hidden
    public ApiResponse<Void> handleMissingServletRequestParameter(MissingServletRequestParameterException exception) {
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, exception.getParameterName() + " 不能为空");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @Hidden
    public ApiResponse<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException exception) {
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, resolveRequestBodyMessage(exception));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    @Hidden
    public ApiResponse<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
        log.error(
                "file upload rejected, scenario=file-upload, reason=max-size-exceeded, message={}",
                exception.getMessage(),
                exception
        );
        return ApiResponse.fail(ErrorCode.FILE_SIZE_EXCEEDED);
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @Hidden
    public ApiResponse<Void> handleAuthenticationCredentialsNotFound(AuthenticationCredentialsNotFoundException exception) {
        return ApiResponse.fail(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @Hidden
    public ApiResponse<Void> handleAccessDenied(AccessDeniedException exception) {
        return ApiResponse.fail(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @Hidden
    public ApiResponse<Void> handleNoResourceFound(NoResourceFoundException exception) {
        return ApiResponse.fail(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @Hidden
    public ApiResponse<Void> handleException(Exception exception) {
        log.error("unhandled server exception, scenario=unhandled-server-exception", exception);
        return ApiResponse.fail(ErrorCode.INTERNAL_ERROR);
    }

    private String resolveRequestBodyMessage(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof InvalidFormatException invalidFormatException
                && invalidFormatException.getValue() instanceof String value
                && value.isEmpty()) {
            return "请求体不能为空";
        }
        if (cause instanceof MismatchedInputException mismatchedInputException
                && mismatchedInputException.getOriginalMessage().contains("No content to map")) {
            return "请求体不能为空";
        }
        if (exception.getMessage() != null && exception.getMessage().contains("Required request body is missing")) {
            return "请求体不能为空";
        }
        return "请求体格式错误";
    }
}

