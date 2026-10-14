package com.repopulse.api;
import com.repopulse.github.GitHubException;
import com.repopulse.repositories.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@RestControllerAdvice
public class ApiExceptionHandler {
    public record ApiError(String code,String message) {}
    @ExceptionHandler(GitHubException.class) public ResponseEntity<ApiError> github(GitHubException e){
        var builder=ResponseEntity.status(e.status());
        if(e.retryAfter()!=null)builder.header("Retry-After",e.retryAfter());
        return builder.body(new ApiError("github_error",e.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<ApiError> invalid(IllegalArgumentException e){return error(400,"invalid_request",e.getMessage());}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed(Exception e){return error(400,"invalid_request","Request body or parameter is invalid");}
    @ExceptionHandler(RepositoryNotFoundException.class) public ResponseEntity<ApiError> missing(RepositoryNotFoundException e){return error(404,"not_found",e.getMessage());}
    @ExceptionHandler(ImportConflictException.class) public ResponseEntity<ApiError> conflict(ImportConflictException e){return error(409,"import_in_progress",e.getMessage());}
    @ExceptionHandler(Exception.class) public ResponseEntity<ApiError> unexpected(Exception e){return error(500,"internal_error","The request could not be completed");}
    private ResponseEntity<ApiError> error(int status,String code,String message){return ResponseEntity.status(status).body(new ApiError(code,message));}
}
