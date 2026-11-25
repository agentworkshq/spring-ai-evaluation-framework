package dev.agentworkshq.eval.core.exception;

public enum ErrorMessages {
    EVAL_E001(500, "Unexpected evaluation error"),
    EVAL_E002(404, "Dataset not found: %s"),
    EVAL_E003(400, "Invalid dataset path: %s"),
    EVAL_E004(415, "Unsupported dataset file type: %s"),
    EVAL_E005(413, "Dataset is too large: %s (limit %d bytes)"),
    EVAL_E006(413, "Dataset has too many cases: %s (limit %d)"),
    EVAL_E007(400, "Malformed dataset content: %s%s"),
    EVAL_E008(422, "Invalid dataset field in %s: %s"),
    EVAL_E009(500, "Dataset could not be read: %s"),
    EVAL_E010(400, "Invalid argument: %s"),
    EVAL_E011(400, "Invalid configuration: %s"),
    EVAL_E012(400, "Invalid run input: %s"),
    EVAL_E013(400, "Too many cases for this run: %d selected (limit %d)"),
    EVAL_E014(400, "No cases selected for this run from dataset: %s"),
    EVAL_E015(400, "Duplicate metric names: %s"),
    EVAL_E016(500, "Evaluation run was cancelled"),
    EVAL_E017(404, "Run not found: %s"),
    EVAL_E018(409, "Run already exists: %s"),
    EVAL_E019(422, "Invalid run file: %s%s"),
    EVAL_E020(400, "Invalid run store location: %s"),
    EVAL_E021(413, "Too many runs in the store: %d (limit %d)"),
    EVAL_E022(422, "Runs are not comparable: %s"),
    EVAL_E023(500, "Run store IO failure during %s"),
    EVAL_E024(400, "Invalid report output: %s"),
    EVAL_E025(500, "Report could not be written: %s");

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
