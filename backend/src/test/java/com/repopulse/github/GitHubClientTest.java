package com.repopulse.github;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GitHubClientTest {
    HttpServer server;
    GitHubClient client;
    Map<String,Reply> replies;
    List<String> requests;
    record Reply(int status,String body,Map<String,String> headers) {}
    @BeforeEach void setup() throws Exception {
        replies=new HashMap<>(); requests=new ArrayList<>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            String path=exchange.getRequestURI().toString(); requests.add(path);
            Reply reply=replies.getOrDefault(path,new Reply(500,"{}",Map.of()));
            reply.headers.forEach((key,value)->exchange.getResponseHeaders().add(key,value));
            byte[] bytes=reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        client=new GitHubClient(URI.create(base()),"secret-for-test",JsonMapper.builder().findAndAddModules().build(),Duration.ofSeconds(10));
        reply("/repos/ada/demo",200,"{\"id\":77,\"owner\":{\"login\":\"ada\"},\"name\":\"demo\",\"default_branch\":\"main\",\"private\":false}");
    }
    @AfterEach void stop(){server.stop(0);}
    String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    void reply(String path,int status,String body){replies.put(path,new Reply(status,body,Map.of()));}
    void link(String path,String body,String next){replies.put(path,new Reply(200,body,Map.of("Link","<"+next+">; rel=\"next\"")));}
    String commit(String sha) {return "{\"sha\":\""+sha+"\",\"commit\":{\"message\":\"Fix login\\nDetails\",\"committer\":{\"date\":\"2026-01-01T00:00:00Z\"},\"author\":{\"name\":\"Ada\",\"email\":\"ada@example.test\"}},\"author\":null}";}
    String file(String name){return "{\"filename\":\""+name+"\",\"status\":\"modified\",\"additions\":4,\"deletions\":2}";}
    void emptyPrs(){reply("/repos/ada/demo/pulls?state=all&sort=updated&direction=desc&per_page=100",200,"[]");}
    void head(){reply("/repos/ada/demo/commits?sha=main&per_page=1",200,"["+commit("abc")+"]");}
    @Test void importsPinnedHistoryAndPaginatedFilesWithoutDuplicatingPaths() {
        head(); emptyPrs();
        link("/repos/ada/demo/commits?sha=abc&per_page=100","["+commit("abc")+"]",base()+"/repos/ada/demo/commits?sha=abc&page=2");
        reply("/repos/ada/demo/commits?sha=abc&page=2",200,"["+commit("def")+"]");
        link("/repos/ada/demo/commits/abc?per_page=100","{\"stats\":{\"total\":12},\"files\":["+file("A.java")+"]}",base()+"/repos/ada/demo/commits/abc?page=2");
        reply("/repos/ada/demo/commits/abc?page=2",200,"{\"files\":["+file("A.java")+","+file("B.java")+"]}");
        reply("/repos/ada/demo/commits/def?per_page=100",200,"{\"stats\":{\"total\":0},\"files\":[]}");
        var s=client.fetchSnapshot(client.repository("ada","demo"));
        assertThat(s.coverage().headSha()).isEqualTo("abc");
        assertThat(s.commits()).hasSize(2);
        assertThat(s.commits().getFirst().files()).hasSize(2);
        assertThat(s.commits().getFirst().author().githubId()).isNull();
        assertThat(s.commits().getFirst().subject()).isEqualTo("Fix login");
        assertThat(s.coverage().filesIncomplete()).isFalse();
        assertThat(requests).contains("/repos/ada/demo/commits?sha=abc&per_page=100");
    }
    @Test void emptyRepositoryHasValidEmptyCoverage() {
        reply("/repos/ada/demo/commits?sha=main&per_page=1",409,"{\"message\":\"Git Repository is empty.\"}"); emptyPrs();
        var s=client.fetchSnapshot(client.repository("ada","demo"));
        assertThat(s.commits()).isEmpty(); assertThat(s.coverage().headSha()).isNull();
    }
    @Test void acceptsGitHubsCanonicalNumericRepositoryPagination() {
        head(); emptyPrs();
        link("/repos/ada/demo/commits?sha=abc&per_page=100","["+commit("abc")+"]",base()+"/repositories/77/commits?sha=abc&page=2");
        reply("/repositories/77/commits?sha=abc&page=2",200,"[]");
        reply("/repos/ada/demo/commits/abc?per_page=100",200,"{\"stats\":{\"total\":0},\"files\":[]}");
        var s=client.fetchSnapshot(client.repository("ada","demo"));
        assertThat(s.commits()).hasSize(1);
        assertThat(s.coverage().commitsCapped()).isFalse();
        assertThat(requests).contains("/repositories/77/commits?sha=abc&page=2");
    }
    @Test void marksCapWhenFinalPageHasMoreItemsThanRemainingCapacity() {
        reply("/repos/ada/demo/commits?sha=main&per_page=1",409,"{\"message\":\"Git Repository is empty.\"}");
        var first=new ArrayList<String>();var second=new ArrayList<String>();
        for(int i=1;i<=120;i++) {
            String p="{\"id\":"+i+",\"number\":"+i+",\"title\":\"PR\",\"state\":\"open\",\"html_url\":\"https://github.com/ada/demo/pull/"+i+"\",\"updated_at\":\"2026-01-01T00:00:00Z\"}";
            (i<=60?first:second).add(p);
        }
        link("/repos/ada/demo/pulls?state=all&sort=updated&direction=desc&per_page=100","["+String.join(",",first)+"]",base()+"/repos/ada/demo/pulls?page=2");
        reply("/repos/ada/demo/pulls?page=2",200,"["+String.join(",",second)+"]");
        var s=client.fetchSnapshot(client.repository("ada","demo"));
        assertThat(s.pullRequests()).hasSize(100);
        assertThat(s.coverage().prsCapped()).isTrue();
    }
    @Test void rejectsPrivateRepositories() {
        reply("/repos/ada/demo",200,"{\"private\":true}");
        assertThatThrownBy(()->client.repository("ada","demo")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInvalidOwnerBeforeAnyRequest() {
        assertThatThrownBy(()->client.repository("../evil","demo")).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }
    @Test void rateLimitRetainsSafeRetryInformation() {
        replies.put("/repos/ada/demo",new Reply(403,"{\"message\":\"secret response\"}",Map.of("X-RateLimit-Remaining","0","Retry-After","60")));
        assertThatThrownBy(()->client.repository("ada","demo")).isInstanceOfSatisfying(GitHubException.class,e->{
            assertThat(e.status()).isEqualTo(503); assertThat(e.retryAfter()).isEqualTo("60"); assertThat(e.getMessage()).doesNotContain("secret");
        });
    }
    @Test void mapsNotFoundAndServerFailures() {
        reply("/repos/ada/demo",404,"{}");
        assertThatThrownBy(()->client.repository("ada","demo")).isInstanceOfSatisfying(GitHubException.class,e->assertThat(e.status()).isEqualTo(404));
        reply("/repos/ada/demo",500,"{}");
        assertThatThrownBy(()->client.repository("ada","demo")).isInstanceOfSatisfying(GitHubException.class,e->assertThat(e.status()).isEqualTo(502));
    }
    @Test void doesNotFollowOffOriginPaginationOrRedirects() {
        head();
        link("/repos/ada/demo/commits?sha=abc&per_page=100","[]","https://evil.example/steal");
        assertThatThrownBy(()->client.fetchSnapshot(client.repository("ada","demo"))).isInstanceOf(GitHubException.class);
        replies.put("/repos/ada/demo",new Reply(302,"{}",Map.of("Location","https://evil.example/steal")));
        assertThatThrownBy(()->client.repository("ada","demo")).isInstanceOf(GitHubException.class);
    }
    @Test void malformedResponseDoesNotBecomeAnEmptySuccess() {
        reply("/repos/ada/demo",200,"{\"name\":\"incomplete\"}");
        assertThatThrownBy(()->client.repository("ada","demo")).isInstanceOf(GitHubException.class);
    }
    @Test void capsCommitsAndPullRequestsAndMarksMissingFileStats() {
        head();
        String commits="["+String.join(",",java.util.stream.IntStream.range(0,100).mapToObj(i->commit("c"+i)).toList())+"]";
        link("/repos/ada/demo/commits?sha=abc&per_page=100",commits,base()+"/repos/ada/demo/commits?page=2");
        for(int i=0;i<100;i++) reply("/repos/ada/demo/commits/c"+i+"?per_page=100",200,"{\"stats\":{\"total\":999},\"files\":[]}");
        String prs="["+String.join(",",java.util.stream.IntStream.range(1,101).mapToObj(i->"{\"id\":"+i+",\"number\":"+i+",\"title\":\"PR\",\"state\":\"open\",\"html_url\":\"https://github.com/ada/demo/pull/"+i+"\",\"updated_at\":\"2026-01-01T00:00:00Z\"}").toList())+"]";
        link("/repos/ada/demo/pulls?state=all&sort=updated&direction=desc&per_page=100",prs,base()+"/repos/ada/demo/pulls?page=2");
        var s=client.fetchSnapshot(client.repository("ada","demo"));
        assertThat(s.commits()).hasSize(100); assertThat(s.pullRequests()).hasSize(100);
        assertThat(s.coverage().commitsCapped()).isTrue(); assertThat(s.coverage().prsCapped()).isTrue();
        assertThat(s.coverage().filesIncomplete()).isTrue();
    }
}
