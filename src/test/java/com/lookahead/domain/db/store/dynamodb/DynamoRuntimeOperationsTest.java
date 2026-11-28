package com.lookahead.domain.db.store.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * DLV-970: the DEV runtime role writes DynamoDB through PartiQL only ({@code
 * dynamodb:PartiQLInsert} alone on the audit table; PutItem, UpdateItem, DeleteItem,
 * TransactWriteItems and BatchWriteItem are denied), so no main source may call a native write. The
 * migration package is held to the same rule: its lock and ledger go through {@link DynamoWrites}
 * too, so the migration-task role needs no native write either. Tests and fixtures may still use
 * native writes to seed or damage a Local store.
 */
class DynamoRuntimeOperationsTest {
  private static final Pattern NATIVE_WRITE =
      Pattern.compile("\\.(putItem|updateItem|deleteItem|transactWriteItems|batchWriteItem)\\(");

  @Test
  void noMainSourceCallsANativeDynamoDbWrite() throws IOException {
    Path main = Path.of("src", "main", "java");
    assertThat(main).isDirectory();
    var offenders = new ArrayList<String>();
    int sources = 0;
    try (Stream<Path> files = Files.walk(main)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        sources++;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int at = 0; at < lines.size(); at++) {
          if (NATIVE_WRITE.matcher(lines.get(at)).find())
            offenders.add(main.relativize(file) + ":" + (at + 1) + " " + lines.get(at).trim());
        }
      }
    }
    assertThat(sources).as("main sources scanned").isGreaterThan(100);
    assertThat(offenders)
        .as("native DynamoDB writes in main sources (the runtime role is PartiQL-only)")
        .isEmpty();
  }

  @Test
  void thePatternCatchesEverySdkWriteOperationAndIgnoresReads() {
    for (String call :
        List.of(
            "client.putItem(request)",
            "client.updateItem(b -> b)",
            "client.deleteItem(x)",
            "client.transactWriteItems(r)",
            "client.batchWriteItem(r)"))
      assertThat(NATIVE_WRITE.matcher(call).find()).as(call).isTrue();
    for (String call :
        List.of(
            "client.getItem(r)",
            "client.query(r)",
            "client.transactGetItems(r)",
            "client.executeTransaction(r)",
            "client.executeStatement(r)",
            "putItemCount(1)")) assertThat(NATIVE_WRITE.matcher(call).find()).as(call).isFalse();
  }
}
