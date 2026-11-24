package dev.agentworkshq.eval.core.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public record EvalDataset(String name, List<EvalCase> cases) {

    private static final String FINGERPRINT_VERSION = "eval-dataset-v1";

    public EvalDataset {
        NameRules.require(name, "name");
        cases = Checks.list(cases, "cases", Integer.MAX_VALUE);
        if (cases.isEmpty()) {
            throw Checks.bad("field 'cases' must not be empty");
        }
        cases = cases.stream().sorted(Comparator.comparing(EvalCase::id)).toList();
        for (int i = 1; i < cases.size(); i++) {
            if (cases.get(i).id().equals(cases.get(i - 1).id())) {
                throw Checks.bad("field 'cases' has a duplicate id '" + cases.get(i).id() + "'");
            }
        }
    }

    public String fingerprint() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            string(out, FINGERPRINT_VERSION);
            string(out, name);
            out.writeInt(cases.size());
            for (EvalCase c : cases) {
                string(out, c.id());
                string(out, c.question());
                out.writeBoolean(c.expectedAnswer() != null);
                if (c.expectedAnswer() != null) {
                    string(out, c.expectedAnswer());
                }
                strings(out, c.expectedSources());
                out.writeBoolean(c.expectNoAnswer());
                strings(out, c.tags());
                out.writeInt(c.attributes().size());
                for (Map.Entry<String, String> e : c.attributes().entrySet()) {
                    string(out, e.getKey());
                    string(out, e.getValue());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void strings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            string(out, value);
        }
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }
}
