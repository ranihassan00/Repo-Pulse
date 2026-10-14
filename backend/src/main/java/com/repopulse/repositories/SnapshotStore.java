package com.repopulse.repositories;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopulse.imports.ImportedSnapshot;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;
import static com.repopulse.repositories.SnapshotModels.*;

@Repository
public class SnapshotStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public SnapshotStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc=jdbc; this.tx=tx; this.json=json;
    }
    public UUID beginImport(RepositoryMetadata repo) {
        try {
            return tx.execute(status -> {
                Long id=jdbc.queryForObject("""
                    INSERT INTO repositories(github_id,owner,name,default_branch) VALUES (?,?,?,?)
                    ON CONFLICT(github_id) DO UPDATE SET owner=excluded.owner,name=excluded.name,default_branch=excluded.default_branch
                    RETURNING id
                    """, Long.class,repo.githubId(),repo.owner(),repo.name(),repo.defaultBranch());
                UUID run=UUID.randomUUID();
                jdbc.update("INSERT INTO import_runs(id,repository_id,status) VALUES (?,?,'running')",run,id);
                return run;
            });
        } catch (DuplicateKeyException conflict) { throw new ImportConflictException(); }
    }
    public RepositorySummary publish(UUID run, ImportedSnapshot snapshot) {
        return tx.execute(status -> {
            var rows=jdbc.queryForList("SELECT repository_id FROM import_runs WHERE id=? AND status='running' FOR UPDATE",run);
            if(rows.isEmpty()) throw new IllegalStateException("Import is not running");
            long id=((Number)rows.getFirst().get("repository_id")).longValue();
            long githubId=jdbc.queryForObject("SELECT github_id FROM repositories WHERE id=?",Long.class,id);
            if(githubId!=snapshot.metadata().githubId()) throw new IllegalArgumentException("Snapshot identity mismatch");
            for(var c:snapshot.commits()) {
                jdbc.update("INSERT INTO commits VALUES (?,?,?,?,?,?,?)", run,c.sha(),c.subject(),Timestamp.from(c.committedAt()),
                    c.author().githubId(),c.author().name(),c.author().email());
                for(var f:c.files()) jdbc.update("INSERT INTO file_changes VALUES (?,?,?,?,?,?,?)",
                    run,c.sha(),f.path(),f.previousPath(),f.status(),f.additions(),f.deletions());
            }
            for(var p:snapshot.pullRequests()) jdbc.update("INSERT INTO pull_requests VALUES (?,?,?,?,?,?,?)",
                run,p.githubId(),p.number(),p.title(),p.state(),p.url(),Timestamp.from(p.updatedAt()));
            jdbc.update("UPDATE import_runs SET status='succeeded',finished_at=now(),coverage=?::jsonb,snapshot=?::jsonb WHERE id=?",
                encode(snapshot.coverage()),encode(snapshot),run);
            jdbc.update("UPDATE repositories SET successful_run=? WHERE id=?",run,id);
            return get(id);
        });
    }
    public void fail(UUID run,String safeError) {
        jdbc.update("UPDATE import_runs SET status='failed',finished_at=now(),error=? WHERE id=? AND status='running'",safeError,run);
    }
    public int recoverAbandonedImports() {
        return jdbc.update("UPDATE import_runs SET status='failed',finished_at=now(),error='Application restarted during import' WHERE status='running'");
    }
    public List<RepositorySummary> list() {
        return jdbc.queryForList("SELECT id FROM repositories ORDER BY id",Long.class).stream().map(this::get).toList();
    }
    public RepositorySummary get(long id) {
        var rows=jdbc.queryForList("""
            SELECT r.*, s.snapshot::text AS snapshot_json,
            (SELECT status FROM import_runs WHERE repository_id=r.id ORDER BY started_at DESC,id DESC LIMIT 1) AS latest_status
            FROM repositories r LEFT JOIN import_runs s ON s.id=r.successful_run WHERE r.id=?
            """,id);
        if(rows.isEmpty()) throw new RepositoryNotFoundException();
        var row=rows.getFirst();
        var repo=new RepositoryMetadata(((Number)row.get("github_id")).longValue(),(String)row.get("owner"),
            (String)row.get("name"),(String)row.get("default_branch"));
        var snapshot=row.get("snapshot_json")==null?null:decode((String)row.get("snapshot_json"));
        long count=snapshot==null?0:snapshot.commits().stream().map(c->authorKey(c.author())).distinct().count();
        return new RepositorySummary(id,repo,snapshot==null?null:snapshot.coverage(),count,(String)row.get("latest_status"));
    }
    public ImportedSnapshot readSnapshot(long id) {
        var rows=jdbc.queryForList("SELECT s.snapshot::text FROM repositories r JOIN import_runs s ON s.id=r.successful_run WHERE r.id=?",String.class,id);
        if(rows.isEmpty()) throw new RepositoryNotFoundException();
        return decode(rows.getFirst());
    }
    private String authorKey(Author a) {
        if(a.githubId()!=null) return "github:"+a.githubId();
        if(a.email()!=null && !a.email().isBlank()) return "email:"+a.email().strip().toLowerCase(Locale.ROOT);
        return "name:"+a.name();
    }
    private String encode(Object value) {
        try {return json.writeValueAsString(value);} catch(JsonProcessingException e){throw new IllegalStateException("Cannot serialize snapshot",e);}
    }
    private ImportedSnapshot decode(String value) {
        try {return json.readValue(value,ImportedSnapshot.class);} catch(JsonProcessingException e){throw new IllegalStateException("Cannot read snapshot",e);}
    }
}
