package com.lookahead.domain.membership;

import java.nio.file.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationInventoryTest {
    @Test void sqlAndJavaMigrationsHaveUniqueVersions() throws Exception {
        var versions=new HashSet<String>();
        // V5 is the explicit Java migration in DomainApiMigration.configure.
        versions.add("5");
        try(var files=Files.list(Path.of("src/main/resources/db/domain"))){
            for(var path:files.filter(p->p.getFileName().toString().matches("V[0-9]+__.+\\.sql")).toList()){
                String version=path.getFileName().toString().split("__")[0].substring(1);
                assertThat(versions.add(version)).as("unique Flyway version for %s",path).isTrue();
            }
        }
    }
}
