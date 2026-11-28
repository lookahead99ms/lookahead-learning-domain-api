package com.lookahead.domain.service.plan;

import static com.lookahead.domain.util.JsonChecks.require;
import static com.lookahead.domain.util.PayloadReaders.*;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.util.CatalogIds;
import com.lookahead.domain.util.StudyPlanJson;
import com.lookahead.domain.validator.StudyPlanReadyMadeTemplateValidator;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Profile("accounts")
public class StudyPlanReadyMadeWriteService {
  private final StudyPlanReadyMadeAdoptionService adoption;
  private final StudyPlanJson json;

  public StudyPlanReadyMadeWriteService(
      StudyPlanReadyMadeAdoptionService adoption, StudyPlanJson json) {
    this.adoption = adoption;
    this.json = json;
  }

  public StudyPlanReadyMadeTemplateValidator.Mapping adopt(
      UUID owner, JsonNode snapshot, JsonNode pins) {
    fields(
        pins,
        "snapshotSchemaVersion origin algorithmVersion catalogVersion rankingVersion template");
    require("ready-made-template".equals(pins.path("origin").asText()), "Invalid template origin");
    var template = pins.path("template");
    fields(
        template,
        "templateId templateVersion templateSha256 pathId pickerCatalogVersion blueprintVersion"
            + " sourceContentVersion adapterVersion");
    // Picker versions identify the discovery index, not the immutable template bytes.
    // Validate that optional client hint, then retain only authoritative template pins.
    if (template.has("pickerCatalogVersion"))
      require(
          text(template, "pickerCatalogVersion", 128)
              .matches("ready-made-catalog/[a-zA-Z0-9._+\\-]{1,100}"),
          "Invalid picker version");
    var comparableTemplate = (ObjectNode) template.deepCopy();
    comparableTemplate.remove("pickerCatalogVersion");
    var prepared =
        adoption.prepare(
            owner, text(template, "templateId", 180), text(template, "templateSha256", 64));
    if (!prepared.coverage().complete())
      throw new AccountFailure(
          403, "CONTENT_ACCESS_DENIED", "Current access must cover every course in this template");
    var mapping = prepared.mapping();
    for (String name : List.of("algorithmVersion", "catalogVersion", "rankingVersion", "template"))
      require(
          mapping
              .provenance()
              .path(name)
              .equals(name.equals("template") ? comparableTemplate : pins.path(name)),
          "Template provenance changed");
    require(
        json.canonical(CatalogIds.normalize(snapshot))
            .equals(json.canonical(CatalogIds.normalize(mapping.snapshot()))),
        "Snapshot differs from the published template");
    return mapping;
  }
}
