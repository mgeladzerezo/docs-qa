package io.github.mgeladzerezo.docsqa.web;

import io.github.mgeladzerezo.docsqa.document.DocumentService.BacklogFullException;
import io.github.mgeladzerezo.docsqa.document.DocumentService.InvalidUploadException;
import io.github.mgeladzerezo.docsqa.document.FileStorage.FileTooLargeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps domain errors to RFC 9457 problem responses with a status a client can act on. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** A request the API understood but will not serve as given, e.g. a question that is too long. */
    public static class BadRequestException extends RuntimeException {

        public BadRequestException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail badRequest(BadRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidUploadException.class)
    ProblemDetail invalidUpload(InvalidUploadException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler(FileTooLargeException.class)
    ProblemDetail tooLarge(FileTooLargeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler(BacklogFullException.class)
    ResponseEntity<ProblemDetail> backlogFull(BacklogFullException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }

    /** Raised by the servlet container before the controller runs, for uploads over the multipart limit. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e,
            HttpHeaders headers,
            org.springframework.http.HttpStatusCode status,
            org.springframework.web.context.request.WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "The file is larger than the upload limit.");
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(body);
    }
}
