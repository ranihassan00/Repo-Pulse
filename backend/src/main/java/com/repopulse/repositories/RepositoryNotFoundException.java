package com.repopulse.repositories;
public class RepositoryNotFoundException extends RuntimeException {
    public RepositoryNotFoundException() { super("Repository or successful snapshot not found"); }
}
