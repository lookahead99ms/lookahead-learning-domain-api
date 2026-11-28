package com.lookahead.domain.service.content;

import com.lookahead.domain.client.PublicationObjectClient;
import com.lookahead.domain.config.PublicationSourceConfigValues;
import com.lookahead.domain.db.store.content.PublicationFileStore;
import com.lookahead.domain.model.PublicationLiveRelease;
import com.lookahead.domain.model.PublicationLocation;
import com.lookahead.domain.model.PublicationSelection;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the content release once, at start, the way the deployment selects it. In cloud mode with a
 * live prefix ({@code app.publication.live-prefix}) the single tar under it is served ({@link
 * PublicationLiveLoadService}); in cloud mode without one, today's live/&lt;tag&gt;/ discovery or
 * the pinned release ({@link PublicationArchiveLoadService}); in Local mode the mounted folders,
 * with nothing to download.
 */
public final class PublicationLoadService {
  private final ObjectMapper mapper;
  private final PublicationFileStore files;

  public PublicationLoadService(ObjectMapper mapper, PublicationFileStore files) {
    this.mapper = mapper;
    this.files = files;
  }

  /** With the deployment's source values: the live prefix, and the mounted Local folders. */
  public PublicationLiveLoadService.Loaded load(
      PublicationSelection selection,
      PublicationSourceConfigValues values,
      Supplier<PublicationObjectClient> source) {
    return load(selection, values.livePrefix(), source, values::localLocation);
  }

  /** {@code source} is asked for only in cloud mode, {@code local} only in Local mode. */
  public PublicationLiveLoadService.Loaded load(
      PublicationSelection selection,
      String livePrefix,
      Supplier<PublicationObjectClient> source,
      Supplier<PublicationLocation> local) {
    if (selection.cloud() && !livePrefix.isEmpty())
      return new PublicationLiveLoadService(mapper, files)
          .load(selection, livePrefix, source.get());
    if (selection.cloud())
      return new PublicationLiveLoadService.Loaded(
          new PublicationArchiveLoadService(mapper, files).load(selection, source.get()),
          PublicationLiveRelease.notConfigured());
    return new PublicationLiveLoadService.Loaded(
        local.get(), PublicationLiveRelease.notConfigured());
  }
}
