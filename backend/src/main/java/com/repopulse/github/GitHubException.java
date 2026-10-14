package com.repopulse.github;
public class GitHubException extends RuntimeException {
    private final int status;
    private final String retryAfter;
    public GitHubException(int status,String message,String retryAfter){super(message);this.status=status;this.retryAfter=retryAfter;}
    public int status(){return status;}
    public String retryAfter(){return retryAfter;}
}
