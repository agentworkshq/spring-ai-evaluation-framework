package dev.agentworkshq.eval.core.exception;

public enum ErrorMessages {
    EVAL_E001(500, "Unexpected evaluation error"),
    EVAL_E002(404, "Dataset not found or unreadable: %s"),
    EVAL_E003(400, "Invalid dataset path: %s"),
    EVAL_E004(415, "Unsupported dataset file type: %s"),
    EVAL_E005(413, "Dataset is too large: %s (limit %d bytes)"),
    EVAL_E006(413, "Dataset has too many cases: %s (limit %d)"),
    EVAL_E007(400, "Malformed dataset content: %s%s"),
    EVAL_E008(422, "Invalid dataset field in %s: %s");

    private final int status;
    private final String message;

    ErrorMessages(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String format(Object... args) {
        return String.format(message, args);
    }
}
