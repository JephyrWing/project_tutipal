package com.my.tutipal_back.learning.focus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * PT-90 AC-10: focus data is used only by the focus feature. No production class outside
 * com.my.tutipal_back.learning.focus imports it (account cleanup deletes the tables by SQL, without importing).
 */
class FocusIsolationTest {

    private static final Path MAIN = Path.of("src/main/java/com/my/tutipal_back");
    private static final Path FOCUS = MAIN.resolve("learning/focus");

    @Test
    void focusPackageExists() {
        assertThat(Files.isDirectory(FOCUS)).as(FOCUS + " exists").isTrue();
    }

    @Test
    void nothingOutsideTheFocusPackageImportsIt() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(FOCUS))
                    .filter(FocusIsolationTest::mentionsFocusPackage)
                    .toList();
        }

        assertThat(offenders).isEmpty();
    }

    private static boolean mentionsFocusPackage(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains("com.my.tutipal_back.learning.focus");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
