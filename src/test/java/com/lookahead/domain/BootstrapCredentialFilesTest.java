package com.lookahead.domain;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class BootstrapCredentialFilesTest {
    @TempDir Path directory;
    Map<String,String> fixture() throws Exception {
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        for(String name:new String[]{"bootstrap.password","app.password","migrator.password"}) {
            Path path=directory.resolve(name);Files.writeString(path,"synthetic-password-123456");
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("r--------"));
        }
        return Map.of("LOOKAHEAD_CREDENTIAL_DIRECTORY",directory.toString());
    }
    @Test void loadsOnlyFixedFilesWithoutMutatingInput() throws Exception {
        var input=fixture();var result=DomainApiBootstrap.credentialEnvironment(input);
        assertThat(result.get("LOOKAHEAD_APP_PASSWORD")).isEqualTo("synthetic-password-123456");
        assertThat(input).doesNotContainKey("LOOKAHEAD_APP_PASSWORD");
        assertThat(DomainApiBootstrap.credentialEnvironment(Map.of("existing","preserved"))).containsEntry("existing","preserved");
    }
    @Test void rejectsReadableWritableAndSymlinkedCredentials() throws Exception {
        var input=fixture();Path file=directory.resolve("app.password");
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        assertThatIllegalStateException().isThrownBy(()->DomainApiBootstrap.credentialEnvironment(input));
        Files.delete(file);Files.createSymbolicLink(file,directory.resolve("bootstrap.password"));
        assertThatIllegalStateException().isThrownBy(()->DomainApiBootstrap.credentialEnvironment(input));
    }
    @Test void rejectsDuplicateSourcesAndOversizedFiles() throws Exception {
        var input=new java.util.HashMap<>(fixture());input.put("LOOKAHEAD_APP_PASSWORD","synthetic-duplicate-secret");
        assertThatIllegalStateException().isThrownBy(()->DomainApiBootstrap.credentialEnvironment(input));
        input.remove("LOOKAHEAD_APP_PASSWORD");Path file=directory.resolve("app.password");
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        Files.writeString(file,"x".repeat(129));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("r--------"));
        assertThatIllegalStateException().isThrownBy(()->DomainApiBootstrap.credentialEnvironment(input));
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatIllegalStateException().isThrownBy(()->DomainApiBootstrap.credentialEnvironment(input));
    }
}
