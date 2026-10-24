package com.wordtrail.common.exception;

import com.wordtrail.common.response.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {
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
        // Do not log SQL exceptions or binding objects, which can contain secrets.
        return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR", "服务暂时不可用"));
    }
}
