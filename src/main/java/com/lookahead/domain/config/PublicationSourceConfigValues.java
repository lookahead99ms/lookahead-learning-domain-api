package com.lookahead.domain.config;

import com.lookahead.domain.model.PublicationLocation;
import org.springframework.core.env.Environment;

/**
 * Where the content release comes from, as the deployment sets it: the live prefix in the assets bucket
 * ({@code app.publication.live-prefix}, empty for today's live/&lt;tag&gt;/ discovery or the pinned release), and in Local
 * mode the three mounted paths. DLV-1016: the configuration's bean method read these four settings itself.
 */
public record PublicationSourceConfigValues(String livePrefix, String localManifest, String localContentRoot, String localAccountCatalog) {
    public static PublicationSourceConfigValues from(Environment environment) {
        return new PublicationSourceConfigValues(environment.getProperty("app.publication.live-prefix", "").strip(),
                environment.getProperty("app.content.publication-path", ""), environment.getProperty("app.content.root", ""),
                environment.getProperty("app.accounts.catalog-path", ""));
    }
    /** The mounted Local folders, with nothing to download. */
    public PublicationLocation localLocation() { return new PublicationLocation(localManifest, localContentRoot, localAccountCatalog, false, 0, 1); }
}
