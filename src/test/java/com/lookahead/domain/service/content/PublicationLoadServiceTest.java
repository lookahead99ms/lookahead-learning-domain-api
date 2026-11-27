package com.lookahead.domain.service.content;

import com.lookahead.domain.db.store.content.PublicationFileStore;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.model.PublicationSelection;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Local mode serves the mounted folders: nothing is downloaded and no object client is asked for. */
class PublicationLoadServiceTest {
    @Test void localModeReturnsTheMountedLocationAndNeverAsksForTheObjectClient() {
        var local = new PublicationLocation("manifest.json", "/content", "catalog.json", false, 0, 1);
        var selection = new PublicationSelection(false, null, null, null, null, null, null, Path.of("/tmp/lookahead-publications"), 0, 1);
        var loaded = new PublicationLoadService(JsonMapper.builder().build(), new PublicationFileStore()).load(selection, "release/live/",
                () -> { throw new AssertionError("Local mode must not ask for the object client"); }, () -> local);
        assertThat(loaded.location()).isSameAs(local);
        assertThat(loaded.release().version()).isNull();
        assertThat(loaded.release().problem()).isNull();
    }
}
