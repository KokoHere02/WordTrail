package com.wordtrail.common.exception;

import com.wordtrail.common.response.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ApiResponse.error("IMPORT_TOO_LARGE","导入文件不能超过 256 KiB"));
    }

    @ExceptionHandler({org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        jakarta.validation.ConstraintViolationException.class})
    ResponseEntity<ApiResponse<Void>> invalidParameter() {
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_PARAMETER", "请求参数不合法"));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> business(BusinessException ex) {
        return ResponseEntity.status(ex.status()).body(ApiResponse.error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getDefaultMessage()).sorted().findFirst().orElse("参数不合法");
        // Never return FieldError or rejected values: they may contain plaintext passwords.
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_PARAMETER", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> invalidJson() {
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_PARAMETER", "请求 JSON 格式或参数类型错误"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
        if (ex instanceof ErrorResponse error) {
            return ResponseEntity.status(error.getStatusCode())
                .body(ApiResponse.error("HTTP_ERROR", "请求无法处理"));
        }
        // Only log exception types, a project source location, and SQL diagnostic codes.
        // Exception messages and stack traces can contain passwords, tokens, or SQL parameters.
        Throwable root=ex;
        while(root.getCause()!=null && root.getCause()!=root) root=root.getCause();
        String location=java.util.Arrays.stream(ex.getStackTrace())
            .filter(frame->frame.getClassName().startsWith("com.wordtrail."))
            .findFirst().map(frame->frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber())
            .orElse("unknown");
        if(root instanceof java.sql.SQLException sql) {
            String state=sql.getSQLState();
            log.error("请求处理失败: exception={}, cause={}, location={}, sqlState={}, sqlCode={}",
                ex.getClass().getSimpleName(),root.getClass().getSimpleName(),location,
                state!=null && state.matches("[A-Za-z0-9]{1,10}")?state:"unknown",sql.getErrorCode());
        } else log.error("请求处理失败: exception={}, cause={}, location={}",
            ex.getClass().getSimpleName(),root.getClass().getSimpleName(),location);
        return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR", "服务暂时不可用"));
    }
}
