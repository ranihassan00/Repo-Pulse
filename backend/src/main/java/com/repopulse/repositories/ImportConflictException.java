package com.repopulse.repositories;
public class ImportConflictException extends RuntimeException {
    public ImportConflictException() { super("An import is already running for this repository"); }
}
