package dev.agentworkshq.eval.core.report;

import dev.agentworkshq.eval.core.compare.CaseChange;
import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.ConfigChange;
import dev.agentworkshq.eval.core.compare.MetricAggregate;
import dev.agentworkshq.eval.core.compare.MetricComparison;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ServiceException;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class ComparisonJson {

    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private ComparisonJson() {
    }

    public static String render(Comparison comparison, ReportOptions options) {
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(options, "options");
        int max = options.maxFieldChars();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("formatVersion", FORMAT_VERSION);
        root.put("baselineRunId", Sanitizer.plain(comparison.baselineRunId(), max));
        root.put("candidateRunId", Sanitizer.plain(comparison.candidateRunId(), max));
        root.put("passesGate", comparison.passesGate());
        root.put("datasetChanged", comparison.datasetChanged());
        root.put("targetChanged", comparison.targetChanged());
        ArrayNode configChanges = root.putArray("configChanges");
        for (ConfigChange c : comparison.configChanges()) {
            ObjectNode node = configChanges.addObject();
            node.put("key", Sanitizer.plain(c.key(), max));
            putText(node, "baselineValue", c.baselineValue(), max);
            putText(node, "candidateValue", c.candidateValue(), max);
        }
        strings(root.putArray("onlyInBaseline"), comparison.onlyInBaseline(), max);
        strings(root.putArray("onlyInCandidate"), comparison.onlyInCandidate(), max);
        ArrayNode metrics = root.putArray("metrics");
        for (MetricComparison m : comparison.metrics()) {
            ObjectNode node = metrics.addObject();
            node.put("metricName", Sanitizer.plain(m.metricName(), max));
            node.put("verdict", m.verdict().name());
            node.put("tolerance", m.tolerance());
            aggregate(node, "baseline", m.baseline());
            aggregate(node, "candidate", m.candidate());
        }
        List<CaseChange> shown = comparison.caseChanges().subList(0, Math.min(comparison.caseChanges().size(), options.maxCaseRows()));
        root.put("caseChangeCount", comparison.caseChanges().size());
        ArrayNode changes = root.putArray("caseChanges");
        for (CaseChange c : shown) {
            ObjectNode node = changes.addObject();
            node.put("caseId", Sanitizer.plain(c.caseId(), max));
            putText(node, "metricName", c.metricName(), max);
            node.put("kind", c.kind().name());
            node.put("baselineStatus", c.baselineStatus());
            putNumber(node, "baselineScore", c.baselineScore());
            node.put("candidateStatus", c.candidateStatus());
            putNumber(node, "candidateScore", c.candidateScore());
        }
        ArrayNode violations = root.putArray("violations");
        comparison.violations().forEach(v -> violations.add(Sanitizer.plain(v, Format.VIOLATION_CHARS)));
        try {
            return MAPPER.writer().with(printer()).writeValueAsString(root) + "\n";
        } catch (JacksonException e) {
            throw new ServiceException(ErrorMessages.EVAL_E025, e, "json");
        }
    }

    private static void aggregate(ObjectNode parent, String name, MetricAggregate a) {
        if (a == null) {
            parent.putNull(name);
            return;
        }
        ObjectNode node = parent.putObject(name);
        node.put("passed", a.passed());
        node.put("failed", a.failed());
        node.put("error", a.error());
        node.put("skipped", a.skipped());
        putNumber(node, "meanScore", a.meanScore());
        putNumber(node, "passRate", a.passRate());
    }

    private static void strings(ArrayNode array, List<String> values, int max) {
        values.forEach(v -> array.add(Sanitizer.plain(v, max)));
    }

    private static void putText(ObjectNode node, String name, String value, int max) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, Sanitizer.plain(value, max));
        }
    }

    private static void putNumber(ObjectNode node, String name, Double value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value.doubleValue());
        }
    }

    private static DefaultPrettyPrinter printer() {
        Separators separators = Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator("");
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        return new DefaultPrettyPrinter(separators).withObjectIndenter(indenter).withArrayIndenter(indenter);
    }
}
