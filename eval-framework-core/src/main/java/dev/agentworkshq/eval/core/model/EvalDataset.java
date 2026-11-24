package dev.agentworkshq.eval.core.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record EvalDataset(String name, List<EvalCase> cases) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public EvalDataset {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("invalid dataset name");
        }
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("cases must not be empty");
        }
        cases = cases.stream().sorted(Comparator.comparing(EvalCase::id)).toList();
        for (int i = 1; i < cases.size(); i++) {
            if (cases.get(i).id().equals(cases.get(i - 1).id())) {
                throw new IllegalArgumentException("duplicate case id");
            }
        }
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public String fingerprint() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, name);
        for (EvalCase c : cases) {
            write(out, c.id());
            write(out, c.question());
            write(out, c.expectedAnswer() == null ? "0" : "1" + c.expectedAnswer());
            write(out, String.valueOf(c.expectedSources().size()));
            c.expectedSources().forEach(s -> write(out, s));
            write(out, String.valueOf(c.expectNoAnswer()));
            write(out, String.valueOf(c.tags().size()));
            c.tags().forEach(t -> write(out, t));
            Map<String, String> sorted = new TreeMap<>(c.attributes());
            write(out, String.valueOf(sorted.size()));
            sorted.forEach((k, v) -> {
                write(out, k);
                write(out, v);
            });
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void write(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(String.valueOf(bytes.length).getBytes(StandardCharsets.US_ASCII));
        out.write(':');
        out.writeBytes(bytes);
    }
}
