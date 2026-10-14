package com.repopulse.api;
import com.repopulse.imports.ImportService;
import com.repopulse.repositories.RepositoryQueryService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.repopulse.repositories.SnapshotModels.*;
@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final ImportService imports;
    private final RepositoryQueryService queries;
    public RepositoryController(ImportService imports,RepositoryQueryService queries){this.imports=imports;this.queries=queries;}
    public record ImportRequest(String owner,String name) {}
    @PostMapping("/import") public RepositorySummary importRepository(@RequestBody ImportRequest body){return imports.importRepository(body.owner(),body.name());}
    @GetMapping public List<RepositorySummary> list(){return queries.list();}
    @GetMapping("/{id}") public RepositorySummary get(@PathVariable long id){return queries.get(id);}
    @GetMapping("/{id}/pull-requests") public PullRequestPage prs(@PathVariable long id,@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size){return queries.pullRequests(id,page,size);}
}
