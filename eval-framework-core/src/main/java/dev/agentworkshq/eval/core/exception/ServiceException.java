package dev.agentworkshq.eval.core.exception;

public class ServiceException extends BaseException {

    public ServiceException(ErrorMessages errorMessage, Object... args) {
        super(errorMessage, args);
    }

    public ServiceException(ErrorMessages errorMessage, Throwable cause, Object... args) {
        super(errorMessage, cause, args);
    }
}
