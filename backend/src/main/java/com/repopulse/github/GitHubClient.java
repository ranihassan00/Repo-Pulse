package com.repopulse.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopulse.imports.ImportedSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.repopulse.repositories.SnapshotModels.*;

@Component
public class GitHubClient {
    private final URI origin;
    private final String token;
    private final ObjectMapper json;
    private final Duration budget;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Pattern NEXT=Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    @Autowired
    public GitHubClient(@Value("${repopulse.github-token:}") String token,ObjectMapper json) {
        this(URI.create("https://api.github.com"),token,json,Duration.ofSeconds(180));
    }
    GitHubClient(URI origin,String token,ObjectMapper json,Duration budget){this.origin=origin;this.token=token;this.json=json;this.budget=budget;}
    public static void validate(String owner,String name) {
        if(owner==null || !owner.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?") ||
            name==null || !name.matches("[A-Za-z0-9_.-]{1,100}") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Provide a valid GitHub owner and repository name");
    }
    public RepositoryMetadata repository(String owner,String name) {
        validate(owner,name);
        JsonNode n=get(uri("/repos/"+owner+"/"+name),deadline(),false).body();
        if(!n.has("private") || !n.get("private").isBoolean()) throw malformed();
        if(n.get("private").booleanValue()) throw new IllegalArgumentException("Only public repositories are supported");
        return new RepositoryMetadata(number(n,"id"),required(n.path("owner"),"login"),required(n,"name"),required(n,"default_branch"));
    }
    public ImportedSnapshot fetchSnapshot(RepositoryMetadata repo) {
        long deadline=deadline();
        String path="/repos/"+repo.owner()+"/"+repo.name();
        Response head=get(uri(path+"/commits?sha="+encode(repo.defaultBranch())+"&per_page=1"),deadline,true);
        List<CommitData> commits=new ArrayList<>();
        String sha=null; boolean commitCap=false,filesIncomplete=false;
        if(head.status()!=409) {
            requireArray(head.body());
            if(!head.body().isEmpty()) {
                sha=required(head.body().get(0),"sha");
                Page sample=pages(uri(path+"/commits?sha="+encode(sha)+"&per_page=100"),100,deadline);
                commitCap=sample.capped();
                Set<String> seen=new HashSet<>();
                for(JsonNode n:sample.items()) {
                    String commitSha=required(n,"sha");
                    if(!seen.add(commitSha)) continue;
                    Files files=files(uri(path+"/commits/"+encode(commitSha)+"?per_page=100"),deadline);
                    filesIncomplete|=files.incomplete();
                    JsonNode details=n.path("commit");
                    JsonNode author=details.path("author");
                    Long authorId=n.path("author").path("id").isIntegralNumber()?n.path("author").path("id").longValue():null;
                    commits.add(new CommitData(commitSha,required(details,"message").split("\\R",2)[0],
                        instant(details.path("committer"),"date"),new Author(authorId,optional(author,"name"),optional(author,"email")),files.items()));
                }
            }
        }
        Page prs=pages(uri(path+"/pulls?state=all&sort=updated&direction=desc&per_page=100"),100,deadline);
        Map<Long,PullRequestData> pullRequests=new LinkedHashMap<>();
        for(JsonNode p:prs.items()) {
            long id=number(p,"id");
            pullRequests.putIfAbsent(id,new PullRequestData(id,Math.toIntExact(number(p,"number")),required(p,"title"),
                required(p,"state"),required(p,"html_url"),instant(p,"updated_at")));
        }
        Instant oldest=commits.stream().map(CommitData::committedAt).min(Comparator.naturalOrder()).orElse(null);
        Instant newest=commits.stream().map(CommitData::committedAt).max(Comparator.naturalOrder()).orElse(null);
        return new ImportedSnapshot(repo,List.copyOf(commits),List.copyOf(pullRequests.values()),new Coverage(sha,100,100,
            commits.size(),pullRequests.size(),commitCap,prs.capped(),filesIncomplete,oldest,newest));
    }
    private Page pages(URI start,int cap,long deadline) {
        List<JsonNode> result=new ArrayList<>(); URI next=start; Set<URI> seen=new HashSet<>();
        int pageCount=0;
        while(next!=null && result.size()<cap) {
            if(!seen.add(next) || ++pageCount>100) throw malformed();
            Response r=get(next,deadline,false); requireArray(r.body());
            boolean omitted=false;
            for(JsonNode n:r.body()) {if(result.size()==cap) {omitted=true;break;} result.add(n);}
            next=r.next();
            if(result.size()==cap) return new Page(result,next!=null || omitted);
        }
        return new Page(result,false);
    }
    private Files files(URI start,long deadline) {
        Map<String,FileChange> files=new LinkedHashMap<>(); URI next=start;
        Set<URI> seen=new HashSet<>(); long total=-1; int pages=0; boolean capped=false;
        while(next!=null) {
            if(!seen.add(next) || ++pages>30) {capped=true;break;}
            Response r=get(next,deadline,false); JsonNode list=r.body().path("files"); requireArray(list);
            if(pages==1) total=number(r.body().path("stats"),"total");
            for(JsonNode f:list) {
                String path=required(f,"filename");
                if(files.size()>=3000 && !files.containsKey(path)) {capped=true;break;}
                files.putIfAbsent(path,new FileChange(path,optional(f,"previous_filename"),required(f,"status"),number(f,"additions"),number(f,"deletions")));
            }
            next=r.next();
            if(files.size()>=3000) {capped=true;break;}
        }
        long observed=files.values().stream().mapToLong(f->f.additions()+f.deletions()).sum();
        return new Files(List.copyOf(files.values()),capped || total!=observed);
    }
    private Response get(URI url,long deadline,boolean allowEmpty) {
        safeUri(url);
        long remaining=deadline-System.nanoTime();
        if(remaining<=0) throw new GitHubException(502,"GitHub import deadline exceeded",null);
        try {
            var builder=HttpRequest.newBuilder(url).timeout(Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(20).toNanos())))
                .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","RepoPulse/0.1");
            if(token!=null&&!token.isBlank()) builder.header("Authorization","Bearer "+token);
            var response=http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());
            int status=response.statusCode();
            if(status==429 || (status==403 && (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0") || response.headers().firstValue("Retry-After").isPresent()))) {
                String retry=response.headers().firstValue("Retry-After").filter(v->v.matches("[0-9]{1,9}")).orElse(null);
                if(retry==null) {
                    String reset=response.headers().firstValue("X-RateLimit-Reset").orElse("");
                    if(reset.matches("[0-9]{1,12}")) retry=Long.toString(Math.max(1,Long.parseLong(reset)-Instant.now().getEpochSecond()));
                }
                throw new GitHubException(503,"GitHub rate limit reached; retry later",retry);
            }
            if(status==404) throw new GitHubException(404,"GitHub repository or resource not found",null);
            JsonNode body;
            try {body=json.readTree(response.body());} catch(Exception e){throw malformed();}
            if(allowEmpty && status==409 && body!=null && body.path("message").asText().toLowerCase(Locale.ROOT).contains("repository is empty"))
                return new Response(status,body,null);
            if(status!=200) throw new GitHubException(502,"GitHub request failed",null);
            if(body==null) throw malformed();
            URI next=null;
            var matcher=NEXT.matcher(response.headers().firstValue("Link").orElse(""));
            if(matcher.find()) {next=url.resolve(matcher.group(1));safeUri(next);}
            return new Response(status,body,next);
        } catch(GitHubException e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new GitHubException(502,"GitHub request interrupted",null);}
        catch(Exception e){throw new GitHubException(502,"GitHub response unavailable or invalid",null);}
    }
    private void safeUri(URI url) {
        // GitHub canonicalizes pagination to numeric repository IDs even when
        // the original request used /repos/{owner}/{name}.
        boolean repositoryPath=url.getPath().startsWith("/repos/") || url.getPath().matches("/repositories/[0-9]+(?:/.*)?");
        if(!Objects.equals(origin.getScheme(),url.getScheme()) || !Objects.equals(origin.getRawAuthority(),url.getRawAuthority()) ||
            url.getUserInfo()!=null || !repositoryPath) throw new GitHubException(502,"Unsafe GitHub pagination URL",null);
    }
    private URI uri(String path){return origin.resolve(path);}
    private long deadline(){return System.nanoTime()+budget.toNanos();}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String required(JsonNode n,String key){JsonNode value=n.get(key);if(value==null||!value.isTextual())throw malformed();return value.textValue();}
    private static String optional(JsonNode n,String key){return n.path(key).isTextual()?n.path(key).textValue():null;}
    private static long number(JsonNode n,String key){JsonNode value=n.get(key);if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw malformed();return value.longValue();}
    private static Instant instant(JsonNode n,String key){try{return Instant.parse(required(n,key));}catch(Exception e){throw malformed();}}
    private static void requireArray(JsonNode n){if(!n.isArray())throw malformed();}
    private static GitHubException malformed(){return new GitHubException(502,"GitHub returned an invalid response",null);}
    private record Response(int status,JsonNode body,URI next) {}
    private record Page(List<JsonNode> items,boolean capped) {}
    private record Files(List<FileChange> items,boolean incomplete) {}
}
