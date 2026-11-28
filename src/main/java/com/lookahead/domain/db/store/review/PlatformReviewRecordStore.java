package com.lookahead.domain.db.store.review;

import static com.lookahead.domain.model.PlatformReviewModels.*;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogThrottle;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-971 (owner, 2026-11-06): one runtime record store in the assets bucket, {@code reviews/} or
 * {@code support-requests/}: a record is {@code records/YYYY/MM/DD/<ID>.json} (the day of its
 * creation, UTC), its attachments {@code attachments/<ID>/NN-name.ext}. Ids are {@code REV-000001}
 * / {@code SUP-000001} from a {@link PlatformReviewRecordNumber} counter. A change reads the
 * record, applies the edit and writes it back only if nobody else wrote it in between; a create
 * uses a fresh id, so it never conflicts. Storage failures answer 503 and a damaged record is
 * explained once a minute without its contents.
 */
public final class PlatformReviewRecordStore {
  static final int WRITE_ATTEMPTS = 8;
  private static final Pattern RECORD_KEY =
      Pattern.compile("records/(\\d{4})/(\\d{2})/(\\d{2})/([A-Z]{3}-\\d{6,})\\.json");
  private static final LogThrottle DAMAGED = new LogThrottle();

  /** A record with the key it lives under and the opaque version of the bytes read. */
  public record Located(String key, StoredRecord record, String version) {}

  private final PlatformReviewObjects objects;
  private final String kind, idPrefix;
  private final ObjectMapper mapper;
  private final PlatformReviewRecordNumber recordNumbers;

  public PlatformReviewRecordStore(
      PlatformReviewObjects objects,
      String kind,
      ObjectMapper mapper,
      PlatformReviewRecordNumber recordNumbers) {
    this.objects = objects;
    this.kind = kind;
    this.mapper = mapper;
    this.recordNumbers = recordNumbers;
    this.idPrefix = KIND_REQUEST.equals(kind) ? "SUP" : "REV";
  }

  public boolean owns(String id) {
    return id != null && id.matches(idPrefix + "-\\d{6,}");
  }

  public PlatformReviewObjects objects() {
    return objects;
  }

  /** The next record id from the sequence: REV-000001, SUP-000001. */
  public String nextId() {
    long number = recordNumbers.next(kind);
    if (number < 1) throw unavailable();
    return idPrefix + "-" + String.format(Locale.ROOT, "%06d", number);
  }

  public static String key(String id, String createdAt) {
    LocalDate day = Instant.parse(createdAt).atZone(ZoneOffset.UTC).toLocalDate();
    return "records/"
        + day.getYear()
        + "/"
        + String.format(Locale.ROOT, "%02d/%02d", day.getMonthValue(), day.getDayOfMonth())
        + "/"
        + id
        + ".json";
  }

  public static String attachmentKey(String id, int index, String name, String extension) {
    String base = name == null ? "" : name.toLowerCase(Locale.ROOT);
    int dot = base.lastIndexOf('.');
    if (dot > 0) base = base.substring(0, dot);
    base = base.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (base.isEmpty()) base = "image";
    if (base.length() > 40) base = base.substring(0, 40).replaceAll("-+$", "");
    return "attachments/"
        + id
        + "/"
        + String.format(Locale.ROOT, "%02d", index)
        + "-"
        + base
        + "."
        + extension;
  }

  /**
   * Every record key with its day, newest day first; an unexpected key under records/ is ignored.
   */
  public Map<String, List<String>> keysByDay() {
    var byDay = new TreeMap<String, List<String>>(Comparator.reverseOrder());
    for (String key : storage(() -> objects.listAll("records/"))) {
      Matcher match = RECORD_KEY.matcher(key);
      if (!match.matches()) continue;
      byDay
          .computeIfAbsent(
              match.group(1) + "-" + match.group(2) + "-" + match.group(3),
              day -> new ArrayList<>())
          .add(key);
    }
    return byDay;
  }

  public Optional<Located> find(String id) {
    if (!owns(id)) return Optional.empty();
    for (var day : keysByDay().entrySet())
      for (String key : day.getValue()) if (key.endsWith("/" + id + ".json")) return read(key);
    return Optional.empty();
  }

  public Optional<Located> read(String key) {
    return storage(() -> objects.read(key))
        .map(stored -> new Located(key, parse(key, stored.bytes()), stored.version()));
  }

  /**
   * Every record of the given keys, in the order given; a key that vanished between list and read
   * is skipped.
   */
  public List<Located> readAll(List<String> keys) {
    var records = new ArrayList<Located>();
    for (String key : keys) read(key).ifPresent(records::add);
    return records;
  }

  public List<Located> all() {
    var keys = new ArrayList<String>();
    keysByDay().values().forEach(keys::addAll);
    return readAll(keys);
  }

  /** Writes a new record; the id is fresh so an existing object means a defect, answered 503. */
  public Located create(StoredRecord record) {
    String key = key(record.id(), record.createdAt());
    byte[] bytes = serialize(record);
    if (!storage(() -> objects.write(key, bytes, null))) throw unavailable();
    return read(key).orElseThrow(PlatformReviewRecordStore::unavailable);
  }

  /**
   * Reads the record, applies {@code edit} and writes the result only when nobody else wrote the
   * record in between; {@code edit} returning null leaves the record unchanged. Null from the whole
   * call: the record is gone.
   */
  public Optional<StoredRecord> change(String key, Function<StoredRecord, StoredRecord> edit) {
    for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
      if (attempt > 0) pause(attempt);
      var current = read(key);
      if (current.isEmpty()) return Optional.empty();
      StoredRecord next = edit.apply(current.get().record());
      if (next == null) return Optional.of(current.get().record());
      byte[] bytes = serialize(next);
      String version = current.get().version();
      if (storage(() -> objects.write(key, bytes, version))) return Optional.of(next);
      if (attempt < WRITE_ATTEMPTS - 1)
        EventLog.retry(
                EventLog.EVENT,
                "platform-review.write",
                "WRITE_CONFLICT",
                attempt + 1,
                WRITE_ATTEMPTS)
            .addKeyValue("target.id", next.id())
            .log(
                "Record {} was changed by another writer at the same moment: attempt {} of {};"
                    + " reading it again",
                next.id(),
                attempt + 1,
                WRITE_ATTEMPTS);
    }
    EventLog.failed(EventLog.EVENT, "platform-review.write", "WRITE_CONFLICT")
        .addKeyValue("retry.max", WRITE_ATTEMPTS)
        .log(
            "Record not saved after {} attempts: another writer kept changing it; refused with 503",
            WRITE_ATTEMPTS);
    throw unavailable();
  }

  public void delete(String key) {
    storage(() -> {
      objects.delete(key);
      return null;
    });
  }

  public void putAttachment(String key, byte[] bytes) {
    storage(() -> {
      objects.put(key, bytes);
      return null;
    });
  }

  public Optional<byte[]> readAttachment(String key) {
    return storage(() -> objects.read(key)).map(PlatformReviewObjects.Stored::bytes);
  }

  /** Best effort: an orphaned image is unreachable without its record. */
  public void discard(List<StoredAttachment> attachments) {
    for (StoredAttachment attachment : attachments) {
      try {
        objects.delete(attachment.path());
      } catch (RuntimeException ignored) {
        /* unreachable without its record */
      }
    }
  }

  private StoredRecord parse(String key, byte[] bytes) {
    try {
      var record = mapper.readValue(bytes, StoredRecord.class);
      if (!RECORD_SCHEMA.equals(record.schema())
          || record.id() == null
          || record.submitter() == null
          || record.attachments() == null) {
        damaged("RECORD_SCHEMA", key, "it is not a " + RECORD_SCHEMA + " record", null);
        throw unavailable();
      }
      return record;
    } catch (JacksonException broken) {
      damaged("RECORD_UNREADABLE", key, "it is not valid JSON for a record", broken);
      throw unavailable();
    }
  }

  private static void damaged(String reason, String key, String words, Exception failure) {
    String types = failure == null ? null : EventLog.types(failure);
    if (!DAMAGED.due(reason + "|" + key)) return;
    var line =
        EventLog.failed(EventLog.EVENT, "platform-review.read", reason)
            .addKeyValue("target.id", id(key));
    if (types != null) line = line.addKeyValue("error.type", types);
    line.log(
        "Record {} cannot be read: {}{}; review pages answer 503 until it is repaired",
        id(key),
        words,
        types == null ? "" : " (" + types + ")");
  }

  private static String id(String key) {
    Matcher match = RECORD_KEY.matcher(key);
    return match.matches() ? match.group(4) : "?";
  }

  byte[] serialize(StoredRecord record) {
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record);
  }

  private static void pause(int attempt) {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(10L, 40L * attempt));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  static <T> T storage(Supplier<T> call) {
    try {
      return call.get();
    } catch (UncheckedIOException failure) {
      throw unavailable();
    }
  }

  // Exception, decided 2026-11-25 (DLV-1016): this store answers its own storage failures (an
  // object call that failed, a
  // write that lost every retry, a damaged record, no record number) as the 503
  // PLATFORM_REVIEW_STORAGE_UNAVAILABLE
  // AccountFailure, and writes the two lines that name the answer ("refused with 503", "review
  // pages answer 503 until
  // it is repaired"). A storage exception type of its own, answered with the same 503 by a method
  // in
  // security.handler.AccountErrorHandler, was not made: the HTTP answer would stay the same, but
  // the failure is also
  // caught by code that prints the type of the exception, and those lines would name the new type
  // where they say
  // AccountFailure today. They are: SupportFeedbackService.record ("... was not recorded for Team
  // Reviews
  // (AccountFailure, PLATFORM_REVIEW_STORAGE_UNAVAILABLE)", key error.type; pinned by
  // SupportFeedbackServiceTest),
  // job.SweepRunner ("The support request close sweep did not complete (AccountFailure)", key
  // error.type) and
  // PlatformReviewService.promote, whose reason is the AccountFailure's code and would become
  // RECORD_NOT_LINKED.
  static AccountFailure unavailable() {
    return new AccountFailure(
        503,
        "PLATFORM_REVIEW_STORAGE_UNAVAILABLE",
        "Review storage is unavailable. Nothing was lost; try again.");
  }
}
