package com.example.staybooking.api.error;

/**
 * 비즈니스 규칙 위반을 {@link ErrorCode}로 표현하는 예외. {@link ApiExceptionHandler}가 에러 봉투로 변환한다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
