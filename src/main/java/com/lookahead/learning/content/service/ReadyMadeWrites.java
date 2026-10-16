package com.lookahead.learning.content.service;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.util.CatalogIds;
import com.lookahead.learning.content.util.PlanJson;
import com.lookahead.learning.content.validator.ReadyMadeTemplateMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static com.lookahead.learning.content.util.PayloadReaders.*;

@Service @Profile("accounts")
public class ReadyMadeWrites {
    private final ReadyMadeAdoptionPreparation preparation;
    private final PlanJson json;
    public ReadyMadeWrites(ReadyMadeAdoptionPreparation preparation,PlanJson json){this.preparation=preparation;this.json=json;}
    public ReadyMadeTemplateMapper.Mapping adopt(UUID owner,JsonNode snapshot,JsonNode pins){
        fields(pins,"snapshotSchemaVersion origin algorithmVersion catalogVersion rankingVersion template");
        require("ready-made-template".equals(pins.path("origin").asText()),"Invalid template origin");
        var template=pins.path("template");
        fields(template,"templateId templateVersion templateSha256 pathId pickerCatalogVersion blueprintVersion sourceContentVersion adapterVersion");
        // Picker versions identify the discovery index, not the immutable template bytes.
        // Validate that optional client hint, then retain only authoritative template pins.
        if(template.has("pickerCatalogVersion"))
            require(text(template,"pickerCatalogVersion",128).matches("ready-made-catalog/[a-zA-Z0-9._+\\-]{1,100}"),"Invalid picker version");
        var comparableTemplate=(tools.jackson.databind.node.ObjectNode)template.deepCopy();
        comparableTemplate.remove("pickerCatalogVersion");
        var prepared=preparation.prepare(owner,text(template,"templateId",180),text(template,"templateSha256",64));
        if(!prepared.coverage().complete())throw new AccountFailure(403,"CONTENT_ACCESS_DENIED","Current access must cover every course in this template");
        var mapping=prepared.mapping();
        for(String name:List.of("algorithmVersion","catalogVersion","rankingVersion","template"))
            require(mapping.provenance().path(name).equals(name.equals("template")?comparableTemplate:pins.path(name)),"Template provenance changed");
        require(json.canonical(CatalogIds.normalize(snapshot)).equals(json.canonical(CatalogIds.normalize(mapping.snapshot()))),"Snapshot differs from the published template");
        return mapping;
    }
}
