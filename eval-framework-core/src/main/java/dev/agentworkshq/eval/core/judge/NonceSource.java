package dev.agentworkshq.eval.core.judge;

import java.security.SecureRandom;
import java.util.HexFormat;

@FunctionalInterface
public interface NonceSource {

    String next();

    static NonceSource secure() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] bytes = new byte[8];
            random.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        };
    }
}
