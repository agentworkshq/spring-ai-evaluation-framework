package dev.agentworkshq.eval.core.report;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;

public record ReportOptions(int maxCaseRows, int maxChars, boolean includeRunConfig, int maxFieldChars) {

    public ReportOptions {
        range(maxCaseRows, 1, 500, "maxCaseRows");
        range(maxChars, 1000, 65000, "maxChars");
        range(maxFieldChars, 10, 200, "maxFieldChars");
    }

    public static ReportOptions defaults() {
        return new ReportOptions(50, 60000, false, 80);
    }

    public ReportOptions withMaxCaseRows(int value) {
        return new ReportOptions(value, maxChars, includeRunConfig, maxFieldChars);
    }

    public ReportOptions withMaxChars(int value) {
        return new ReportOptions(maxCaseRows, value, includeRunConfig, maxFieldChars);
    }

    public ReportOptions withIncludeRunConfig(boolean value) {
        return new ReportOptions(maxCaseRows, maxChars, value, maxFieldChars);
    }

    public ReportOptions withMaxFieldChars(int value) {
        return new ReportOptions(maxCaseRows, maxChars, includeRunConfig, value);
    }

    private static void range(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new ValidationException(ErrorMessages.EVAL_E010, field + " must be between " + min + " and " + max);
        }
    }
}
