package com.repopulse.repositories;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.repopulse.imports.ImportedSnapshot;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import static com.repopulse.repositories.SnapshotModels.*;
import static org.assertj.core.api.Assertions.*;

class SnapshotStoreTest {
    static EmbeddedPostgres postgres;
    JdbcTemplate jdbc;
    SnapshotStore store;
    static final RepositoryMetadata REPO = new RepositoryMetadata(77, "octocat", "demo", "main");
    @BeforeAll static void start() throws Exception { postgres = EmbeddedPostgres.start(); }
    @AfterAll static void stop() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach void setup() {
        var ds = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("TRUNCATE repositories, import_runs, commits, file_changes, pull_requests CASCADE");
        store = new SnapshotStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)),
            JsonMapper.builder().findAndAddModules().build());
    }
    static ImportedSnapshot snapshot() {
        var time = Instant.parse("2026-01-01T12:00:00Z");
        return new ImportedSnapshot(REPO, List.of(new CommitData("abc", "fix login", time,
            new Author(null, "Ada", "ada@example.test"), List.of(new FileChange("Auth.java", null, "modified", 4, 2)))),
            List.of(new PullRequestData(12, 1, "Login", "open", "https://github.com/octocat/demo/pull/1", time)),
            new Coverage("abc", 100, 100, 1, 1, false, false, false, time, time));
    }
    @Test void reimportDoesNotInflateVisibleCountsAndRenameRetainsIdentity() {
        var first = store.publish(store.beginImport(REPO), snapshot());
        var renamed = new RepositoryMetadata(77, "Octocat", "renamed", "main");
        var old = snapshot();
        var second = store.publish(store.beginImport(renamed), new ImportedSnapshot(renamed, old.commits(), old.pullRequests(), old.coverage()));
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.list()).hasSize(1);
        assertThat(second.coverage().commitCount()).isEqualTo(1);
        assertThat(second.sampledContributorCount()).isEqualTo(1);
        assertThat(store.readSnapshot(first.id()).commits()).hasSize(1);
    }
    @Test void failedRefreshKeepsSuccessfulSnapshot() {
        var first = store.publish(store.beginImport(REPO), snapshot());
        store.fail(store.beginImport(REPO), "Upstream unavailable");
        assertThat(store.get(first.id()).latestRunStatus()).isEqualTo("failed");
        assertThat(store.readSnapshot(first.id()).commits().getFirst().sha()).isEqualTo("abc");
    }
    @Test void concurrentImportIsRejectedAndRecoveryReleasesSlot() {
        store.beginImport(REPO);
        assertThatThrownBy(() -> store.beginImport(REPO)).isInstanceOf(ImportConflictException.class);
        assertThat(store.recoverAbandonedImports()).isEqualTo(1);
        assertThatCode(() -> store.beginImport(REPO)).doesNotThrowAnyException();
    }
    @Test void duplicateCommitRollsBackPublish() {
        var first = store.publish(store.beginImport(REPO), snapshot());
        var s = snapshot();
        var run = store.beginImport(REPO);
        assertThatThrownBy(() -> store.publish(run, new ImportedSnapshot(REPO,
            List.of(s.commits().getFirst(), s.commits().getFirst()), s.pullRequests(), s.coverage())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(store.readSnapshot(first.id()).commits()).hasSize(1);
        store.fail(run, "Duplicate commit");
    }
    @Test void duplicatePathIsRejectedByDatabase() {
        var s = snapshot(); var c = s.commits().getFirst(); var run = store.beginImport(REPO);
        var duplicate = new CommitData(c.sha(), c.subject(), c.committedAt(), c.author(), List.of(c.files().getFirst(), c.files().getFirst()));
        assertThatThrownBy(() -> store.publish(run, new ImportedSnapshot(REPO, List.of(duplicate), s.pullRequests(), s.coverage())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
