package dev.agentworkshq.eval.core.exception;

public class ResourceNotFoundException extends BaseException {

    public ResourceNotFoundException(ErrorMessages errorMessage, Object... args) {
        super(errorMessage, args);
    }

    public ResourceNotFoundException(ErrorMessages errorMessage, Throwable cause, Object... args) {
        super(errorMessage, cause, args);
    }
}
