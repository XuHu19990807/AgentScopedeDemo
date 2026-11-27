package com.cetc28.identity;

import org.springframework.http.HttpStatus;

/**
 * 身份相关业务异常，携带 HTTP 状态码，由控制器直接转成响应。
 */
public class IdentityException extends RuntimeException {

    private final HttpStatus status;

    public IdentityException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static IdentityException badRequest(String message) {
        return new IdentityException(HttpStatus.BAD_REQUEST, message);
    }

    public static IdentityException unauthorized(String message) {
        return new IdentityException(HttpStatus.UNAUTHORIZED, message);
    }

    public static IdentityException forbidden(String message) {
        return new IdentityException(HttpStatus.FORBIDDEN, message);
    }

    public static IdentityException notFound(String message) {
        return new IdentityException(HttpStatus.NOT_FOUND, message);
    }

    public static IdentityException conflict(String message) {
        return new IdentityException(HttpStatus.CONFLICT, message);
    }

    public static IdentityException unavailable(String message) {
        return new IdentityException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static IdentityException tooManyRequests(String message) {
        return new IdentityException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
