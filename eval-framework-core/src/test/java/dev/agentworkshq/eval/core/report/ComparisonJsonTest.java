package dev.agentworkshq.eval.core.report;

import static dev.agentworkshq.eval.core.report.ReportFixtures.comparison;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ComparisonJsonTest {

    private static final ReportOptions OPTIONS = ReportOptions.defaults();

    private static JsonNode parse(String json) {
        JsonFactory factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .build().readTree(json);
    }

    @Test
    void matchesTheGoldenFile() {
        String json = ComparisonJson.render(comparison(), OPTIONS);

        assertThat(json).isEqualTo(ReportFixtures.resource("comparison-json.golden"));
        assertThat(json).doesNotContain("\r").endsWith("}\n").doesNotEndWith("\n\n");
    }

    @Test
    void readsBackStrictlyWithTheExpectedFields() {
        JsonNode root = parse(ComparisonJson.render(comparison(), OPTIONS));

        List<String> names = new ArrayList<>();
        root.propertyNames().forEach(names::add);
        assertThat(names).containsExactly("formatVersion", "baselineRunId", "candidateRunId", "passesGate", "datasetChanged",
                "targetChanged", "configChanges", "onlyInBaseline", "onlyInCandidate", "metrics", "caseChangeCount",
                "caseChanges", "violations");
        assertThat(root.get("formatVersion").intValue()).isEqualTo(1);
        assertThat(root.get("passesGate").booleanValue()).isFalse();
        assertThat(root.get("metrics")).hasSize(5);
        assertThat(root.get("metrics").get(2).get("verdict").stringValue()).isEqualTo("REGRESSED");
        assertThat(root.get("metrics").get(2).get("baseline").get("meanScore").doubleValue()).isEqualTo(2.0 / 3);
        assertThat(root.get("metrics").get(4).get("candidate").get("meanScore").isNull()).isTrue();
        assertThat(root.get("caseChangeCount").intValue()).isEqualTo(5);
        assertThat(root.get("caseChanges")).hasSize(5);
        assertThat(root.get("violations")).hasSize(6);
    }

    @Test
    void caseChangesAreCappedButCounted() {
        JsonNode root = parse(ComparisonJson.render(comparison(), OPTIONS.withMaxCaseRows(2)));

        assertThat(root.get("caseChangeCount").intValue()).isEqualTo(5);
        assertThat(root.get("caseChanges")).hasSize(2);
    }

    @Test
    void renderingTwiceGivesTheSameText() {
        assertThat(ComparisonJson.render(comparison(), OPTIONS)).isEqualTo(ComparisonJson.render(comparison(), OPTIONS));
    }
}
