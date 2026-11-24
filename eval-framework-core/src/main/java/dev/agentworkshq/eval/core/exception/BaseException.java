package dev.agentworkshq.eval.core.exception;

public abstract class BaseException extends RuntimeException {

    private final ErrorMessages errorMessage;

    protected BaseException(ErrorMessages errorMessage, Object... args) {
        super(errorMessage.format(args));
        this.errorMessage = errorMessage;
    }

    protected BaseException(ErrorMessages errorMessage, Throwable cause, Object... args) {
        super(errorMessage.format(args), cause);
        this.errorMessage = errorMessage;
    }

    public ErrorMessages getErrorMessage() {
        return errorMessage;
    }

    public String getErrorCode() {
        return errorMessage.name();
    }

    public int getStatus() {
        return errorMessage.getStatus();
    }
}
