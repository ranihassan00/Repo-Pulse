package com.repopulse.imports;
import com.repopulse.repositories.SnapshotStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
@Component
public class ImportRecovery implements ApplicationRunner {
    private final SnapshotStore store;
    public ImportRecovery(SnapshotStore store){this.store=store;}
    @Override public void run(ApplicationArguments args){store.recoverAbandonedImports();}
}
