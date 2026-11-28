package com.lookahead.domain.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps every Java source line within the Google Java Style column limit of 100 (section 4.4).
 *
 * <p>google-java-format, run by the Spotless check in {@code verify}, wraps code but cannot shorten
 * a long line comment, a text-block line or a string without spaces. The guide's exceptions apply:
 * package and import lines, and lines that contain a URL.
 */
class SourceLineLengthTest {
  private static final int COLUMN_LIMIT = 100;

  @Test
  void javaSourceLinesFitTheColumnLimit() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
      assertThat(root).isDirectory();
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
          List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
          for (int index = 0; index < lines.size(); index++) {
            if (tooLong(lines.get(index))) {
              violations.add(file + ":" + (index + 1));
            }
          }
        }
      }
    }
    assertThat(violations).as("Java lines over %d columns", COLUMN_LIMIT).isEmpty();
  }

  @Test
  void onlyPackageImportAndUrlLinesMayExceedTheLimit() {
    String filler = "x".repeat(COLUMN_LIMIT);
    assertThat(tooLong("x".repeat(COLUMN_LIMIT))).isFalse();
    assertThat(tooLong("  // " + filler)).isTrue();
    assertThat(tooLong("    String sql = \"" + filler + "\";")).isTrue();
    assertThat(tooLong("import org.example." + filler + ";")).isFalse();
    assertThat(tooLong("package org.example." + filler + ";")).isFalse();
    assertThat(tooLong("   * See https://example.test/" + filler)).isFalse();
  }

  static boolean tooLong(String line) {
    if (line.codePointCount(0, line.length()) <= COLUMN_LIMIT) {
      return false;
    }
    String code = line.strip();
    return !(code.startsWith("package ") || code.startsWith("import ") || code.contains("://"));
  }
}
