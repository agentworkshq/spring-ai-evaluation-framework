package dev.agentworkshq.eval.core.suite;

import dev.agentworkshq.eval.core.compare.ComparisonConfig;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.NameRules;
import dev.agentworkshq.eval.core.report.ReportOptions;
import dev.agentworkshq.eval.core.runner.RunConfig;

public record EvalRequest(
        RunConfig runConfig,
        String baselineRunId,
        Thresholds thresholds,
        ComparisonConfig comparison,
        boolean saveRun,
        boolean writeReports,
        ReportOptions reportOptions) {

    public EvalRequest {
        if (runConfig == null || thresholds == null || comparison == null || reportOptions == null) {
            throw new ValidationException(ErrorMessages.EVAL_E010,
                    "runConfig, thresholds, comparison and reportOptions must not be null");
        }
        if (baselineRunId != null) {
            NameRules.require(baselineRunId, "baselineRunId");
        }
    }

    public static EvalRequest defaults() {
        return new EvalRequest(RunConfig.defaults(), null, Thresholds.defaults(), ComparisonConfig.defaults(), true, true,
                ReportOptions.defaults());
    }

    public EvalRequest withRunConfig(RunConfig value) {
        return new EvalRequest(value, baselineRunId, thresholds, comparison, saveRun, writeReports, reportOptions);
    }

    public EvalRequest withBaselineRunId(String value) {
        return new EvalRequest(runConfig, value, thresholds, comparison, saveRun, writeReports, reportOptions);
    }

    public EvalRequest withThresholds(Thresholds value) {
        return new EvalRequest(runConfig, baselineRunId, value, comparison, saveRun, writeReports, reportOptions);
    }

    public EvalRequest withComparison(ComparisonConfig value) {
        return new EvalRequest(runConfig, baselineRunId, thresholds, value, saveRun, writeReports, reportOptions);
    }

    public EvalRequest withSaveRun(boolean value) {
        return new EvalRequest(runConfig, baselineRunId, thresholds, comparison, value, writeReports, reportOptions);
    }

    public EvalRequest withWriteReports(boolean value) {
        return new EvalRequest(runConfig, baselineRunId, thresholds, comparison, saveRun, value, reportOptions);
    }

    public EvalRequest withReportOptions(ReportOptions value) {
        return new EvalRequest(runConfig, baselineRunId, thresholds, comparison, saveRun, writeReports, value);
    }

    @Override
    public String toString() {
        return "EvalRequest[runId=" + runConfig.runId() + ", baselineRunId=" + baselineRunId + ", saveRun=" + saveRun
                + ", writeReports=" + writeReports + "]";
    }
}
