package pro.teaz.pulse.containers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/** Docker API unreachable or failing (socket proxy down, engine error): 503 instead of a bare 500. */
@RestControllerAdvice
public class DockerErrorHandler {

	private static final Logger log = LoggerFactory.getLogger(DockerErrorHandler.class);

	@ExceptionHandler(RestClientException.class)
	public ProblemDetail dockerUnavailable(RestClientException e) {
		log.warn("Docker API call failed", e);
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Docker API not available.");
	}
}
