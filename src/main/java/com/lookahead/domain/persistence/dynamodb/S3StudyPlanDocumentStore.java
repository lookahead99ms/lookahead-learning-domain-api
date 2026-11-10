package com.lookahead.domain.persistence.dynamodb;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Hybrid upload/publication primitive. Caller owns plan authorization; no runtime wiring or S3 cleanup. */
public final class S3StudyPlanDocumentStore {
    public record Stage(S3StudyPlanDocumentObjects.Descriptor descriptor, Instant abandonAfter) {
        public Stage {
            Objects.requireNonNull(descriptor); Objects.requireNonNull(abandonAfter);
            if (abandonAfter.isBefore(Instant.EPOCH) || abandonAfter.getEpochSecond() > 253402300799L || abandonAfter.getNano() != 0)
                throw new IllegalArgumentException("Whole-second supported staging deadline required");
        }
        String pk() { return "ACCOUNT#" + descriptor.id().ownerId(); }
        String sk() { return "S3DOCUMENT#" + descriptor.id().planId() + "#" + descriptor.id().documentId(); }
        String jobKey() { return "DUE#" + String.format(Locale.ROOT,"%012d",abandonAfter.getEpochSecond()) + "#"
                + descriptor.id().ownerId() + "#" + descriptor.id().planId() + "#" + descriptor.id().documentId(); }
    }
    /** Can only be minted by verified, lifecycle-checked publication below. */
    static final class VerifiedDocument {
        private final Stage stage;
        private final S3StudyPlanDocumentObjects.Reference reference;
        private final String bucket;
        private final UUID operationId;
        private final boolean replayOnly;
        private VerifiedDocument(Stage stage, S3StudyPlanDocumentObjects.Reference reference, String bucket, UUID operationId, boolean replayOnly) {
            this.stage=stage;this.reference=reference;this.bucket=bucket;this.operationId=operationId;this.replayOnly=replayOnly;
        }
        boolean replayOnly() { return replayOnly; }
        String fingerprintScope() { return "s3-document-publication/v1|"+stage.abandonAfter().getEpochSecond(); }
        List<ParameterizedStatement> publicationStatements(DynamoMetadataTables tables) {
            return List.of(statement("UPDATE \""+tables.userAccountDetailsTableName()+"\" SET lifecycle=? SET publicationId=?"
                    +" WHERE pk=? AND sk=? AND schemaVersion=1 AND lifecycle=? AND sha256=? AND byteLength=?"
                    +" AND bucket=? AND objectKey=? AND objectVersion=? AND objectETag=? AND abandonAfter=?",
                    s("COMMITTED"),s(operationId.toString()),s(stage.pk()),s(stage.sk()),s("UPLOADED"),s(stage.descriptor().sha256()),
                    n(stage.descriptor().byteLength()),s(bucket),s(stage.descriptor().id().key()),s(reference.versionId()),s(reference.eTag()),n(stage.abandonAfter().getEpochSecond())),
                    statement("DELETE FROM \""+tables.userAccountDetailsTableName()+"\" WHERE pk=? AND sk=? AND schemaVersion=1 AND manifestKey=?",
                            s("WORK#S3DOCUMENT#00"),s(stage.jobKey()),s(stage.sk())));
        }
    }
    private final DynamoDbClient client;
    private final DynamoMetadataTables tables;
    private final S3StudyPlanDocumentObjects objects;
    private final Clock clock;
    public S3StudyPlanDocumentStore(DynamoDbClient client, DynamoMetadataTables tables, S3StudyPlanDocumentObjects objects, Clock clock) {
        this.client=Objects.requireNonNull(client);this.tables=Objects.requireNonNull(tables);
        this.objects=Objects.requireNonNull(objects);this.clock=Objects.requireNonNull(clock);
        if (!tables.userAccountDetailsTableName().startsWith("lookahead-"+objects.settings().environment()+"-"))
            throw new IllegalArgumentException("S3 and metadata environments differ");
    }
    public Stage stage(S3StudyPlanDocumentObjects.Id id, byte[] document, Instant abandonAfter) {
        byte[] bytes=Objects.requireNonNull(document).clone();
        var stage=new Stage(S3StudyPlanDocumentObjects.Descriptor.of(id,bytes),abandonAfter);
        begin(stage);
        var prior=manifest(id);validate(stage,prior);
        if ("COMMITTED".equals(state(prior))) return stage;
        requireUnexpired(stage);
        if ("UPLOADED".equals(state(prior))) { objects.read(reference(stage,prior));return stage; }
        if (!"STAGING".equals(state(prior))) throw new IllegalStateException("Document cannot be uploaded");
        var reference=objects.upload(stage.descriptor(),bytes);
        requireUnexpired(stage);
        try {
            client.updateItem(UpdateItemRequest.builder().tableName(tables.userAccountDetailsTableName()).key(key(stage.pk(),stage.sk()))
                    .updateExpression("SET lifecycle=:uploaded, objectVersion=:version, objectETag=:etag")
                    .conditionExpression("schemaVersion=:schema AND lifecycle=:staging AND sha256=:sha AND abandonAfter=:deadline")
                    .expressionAttributeValues(Map.of(":uploaded",s("UPLOADED"),":version",s(reference.versionId()),":etag",s(reference.eTag()),
                            ":schema",n(1),":staging",s("STAGING"),":sha",s(stage.descriptor().sha256()),":deadline",n(stage.abandonAfter().getEpochSecond()))).build());
        } catch (SdkException failure) {
            try {
                var recovered=manifest(id);validate(stage,recovered);
                if (List.of("UPLOADED","COMMITTED").contains(state(recovered)) && reference.equals(reference(stage,recovered))) return stage;
            } catch (RuntimeException readFailure) {failure.addSuppressed(readFailure);}
            throw failure;
        }
        return stage;
    }
    public void begin(Stage stage) {
        var prior=manifest(stage.descriptor().id());
        if (!prior.isEmpty()) {
            validate(stage,prior);
            if ("DELETING".equals(state(prior)))throw new IllegalStateException("Document is being deleted");
            if (!"COMMITTED".equals(state(prior)))requireUnexpired(stage);
            return;
        }
        requireUnexpired(stage);
        var item=new HashMap<>(key(stage.pk(),stage.sk()));
        item.putAll(Map.of("schemaVersion",n(1),"storageKind",s("S3"),"lifecycle",s("STAGING"),
                "sha256",s(stage.descriptor().sha256()),"byteLength",n(stage.descriptor().byteLength()),"bucket",s(objects.settings().bucket()),
                "objectKey",s(stage.descriptor().id().key()),"abandonAfter",n(stage.abandonAfter().getEpochSecond())));
        var job=new HashMap<>(key("WORK#S3DOCUMENT#00",stage.jobKey()));
        job.putAll(Map.of("schemaVersion",n(1),"manifestKey",s(stage.sk()),"ownerId",s(stage.descriptor().id().ownerId().toString())));
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(
                    TransactWriteItem.builder().put(Put.builder().tableName(tables.userAccountDetailsTableName()).item(item).conditionExpression("attribute_not_exists(pk)").build()).build(),
                    TransactWriteItem.builder().put(Put.builder().tableName(tables.userAccountDetailsTableName()).item(job).conditionExpression("attribute_not_exists(pk)").build()).build()).build());
        } catch (SdkException failure) {
            try {var recovered=manifest(stage.descriptor().id());validate(stage,recovered);
                if (!"DELETING".equals(state(recovered)))return;
            } catch (RuntimeException readFailure) {failure.addSuppressed(readFailure);}
            throw failure;
        }
    }
    public AuditedAccountStore.CommitResult publish(Stage stage, UUID operationId, long expectedRevision, AuditedAccountStore.AuditEvent audit) {
        Objects.requireNonNull(operationId);
        var item=manifest(stage.descriptor().id());validate(stage,item);
        var reference=reference(stage,item);
        var verified=new VerifiedDocument(stage,reference,objects.settings().bucket(),operationId,"COMMITTED".equals(state(item)));
        if ("COMMITTED".equals(state(item))) {
            if (!s(operationId.toString()).equals(item.get("publicationId"))) throw new IllegalStateException("Document already published by another operation");
        } else {
            if (!"UPLOADED".equals(state(item))) throw new IllegalStateException("Document is not uploaded");
            requireUnexpired(stage);objects.read(reference);requireUnexpired(stage);
        }
        return new AuditedAccountStore(client,tables).commitS3Document(new AuditedAccountStore.Mutation(stage.descriptor().id().ownerId(),
                recordId(stage.descriptor().id().planId()),operationId,expectedRevision,pointer(reference),audit),verified);
    }
    public byte[] readPublished(S3StudyPlanDocumentObjects.Id id) {
        var item=manifest(id);var stage=parse(id,item);validate(stage,item);
        if (!"COMMITTED".equals(state(item))) throw new IllegalStateException("Document is not published");
        return objects.read(reference(stage,item));
    }
    static String recordId(UUID planId) {return "study-plan-document#"+planId;}
    private SdkBytes pointer(S3StudyPlanDocumentObjects.Reference reference) {
        var d=reference.descriptor();var text=new StringBuilder("s3-document/v1");
        for(String value:List.of(objects.settings().bucket(),d.id().key(),reference.versionId(),reference.eTag(),d.sha256(),Integer.toString(d.byteLength())))
            text.append('|').append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        return SdkBytes.fromUtf8String(text.toString());
    }
    private Map<String,AttributeValue> manifest(S3StudyPlanDocumentObjects.Id id) {
        return client.getItem(GetItemRequest.builder().tableName(tables.userAccountDetailsTableName())
                .key(key("ACCOUNT#"+id.ownerId(),"S3DOCUMENT#"+id.planId()+"#"+id.documentId())).consistentRead(true).build()).item();
    }
    private void validate(Stage expected, Map<String,AttributeValue> item) {
        if (!expected.equals(parse(expected.descriptor().id(),item)) || !s(objects.settings().bucket()).equals(item.get("bucket"))
                || !s(expected.descriptor().id().key()).equals(item.get("objectKey"))) throw new IllegalStateException("Document intent changed");
    }
    private static Stage parse(S3StudyPlanDocumentObjects.Id id, Map<String,AttributeValue> item) {
        state(item);
        try {return new Stage(new S3StudyPlanDocumentObjects.Descriptor(id,Integer.parseInt(item.get("byteLength").n()),item.get("sha256").s()),
                Instant.ofEpochSecond(Long.parseLong(item.get("abandonAfter").n())));}
        catch (NullPointerException|IllegalArgumentException failure){throw new IllegalStateException("Invalid S3 document manifest",failure);}
    }
    private static S3StudyPlanDocumentObjects.Reference reference(Stage stage, Map<String,AttributeValue> item) {
        try {return new S3StudyPlanDocumentObjects.Reference(stage.descriptor(),item.get("objectVersion").s(),item.get("objectETag").s());}
        catch (NullPointerException failure){throw new IllegalStateException("Missing S3 version reference",failure);}
    }
    private static String state(Map<String,AttributeValue> item) {
        if (!n(1).equals(item.get("schemaVersion")) || !s("S3").equals(item.get("storageKind")) || item.get("lifecycle")==null
                || !List.of("STAGING","UPLOADED","COMMITTED","DELETING").contains(item.get("lifecycle").s()))
            throw new IllegalStateException("Missing or invalid S3 document state");
        return item.get("lifecycle").s();
    }
    private void requireUnexpired(Stage stage) {if(!clock.instant().isBefore(stage.abandonAfter()))throw new IllegalStateException("Document staging deadline expired");}
    private static Map<String,AttributeValue> key(String pk,String sk){return Map.of("pk",s(pk),"sk",s(sk));}
    private static AttributeValue s(String value){return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value){return AttributeValue.builder().n(Long.toString(value)).build();}
    private static ParameterizedStatement statement(String sql,AttributeValue... values){return ParameterizedStatement.builder().statement(sql).parameters(values).build();}
}
