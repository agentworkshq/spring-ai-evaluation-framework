package dev.agentworkshq.eval.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class ErrorMessagesTest {

    @Test
    void namesStartWithPrefixAndAreUnique() {
        assertThat(Arrays.stream(ErrorMessages.values()).map(Enum::name)).allMatch(n -> n.startsWith("EVAL_E"));
        assertThat(new HashSet<>(Arrays.stream(ErrorMessages.values()).map(Enum::name).toList()))
                .hasSize(ErrorMessages.values().length);
    }

    @Test
    void statusesAreHttpStyle() {
        assertThat(Arrays.stream(ErrorMessages.values()).mapToInt(ErrorMessages::getStatus)).allMatch(s -> s >= 400 && s < 600);
    }

    @Test
    void formatFillsArguments() {
        assertThat(ErrorMessages.EVAL_E005.format("a.json", 10)).isEqualTo("Dataset is too large: a.json (limit 10 bytes)");
        assertThat(ErrorMessages.EVAL_E010.format("field 'x'")).isEqualTo("Invalid argument: field 'x'");
    }

    @Test
    void exceptionsKeepCauseCodeAndStatus() {
        RuntimeException cause = new RuntimeException("boom");

        BaseException validation = new ValidationException(ErrorMessages.EVAL_E010, cause, "x");
        BaseException notFound = new ResourceNotFoundException(ErrorMessages.EVAL_E002, cause, "x");
        BaseException service = new ServiceException(ErrorMessages.EVAL_E009, cause, "x");

        for (BaseException e : new BaseException[] {validation, notFound, service}) {
            assertThat(e.getCause()).isSameAs(cause);
            assertThat(e.getErrorCode()).isEqualTo(e.getErrorMessage().name());
            assertThat(e.getStatus()).isEqualTo(e.getErrorMessage().getStatus());
        }
        assertThat(validation.getMessage()).isEqualTo("Invalid argument: x");
    }

    @Test
    void exceptionsWithoutCauseHaveNone() {
        assertThat(new ValidationException(ErrorMessages.EVAL_E011, "x").getCause()).isNull();
        assertThat(new ServiceException(ErrorMessages.EVAL_E001).getErrorCode()).isEqualTo("EVAL_E001");
        assertThat(new ResourceNotFoundException(ErrorMessages.EVAL_E002, "x").getErrorCode()).isEqualTo("EVAL_E002");
    }
}
