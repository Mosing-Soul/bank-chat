package org.gundy.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.exception.ApplicationException;
import org.gundy.chat.exception.ErrorCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;

@Slf4j
final class AiServiceCallSupport {
    private AiServiceCallSupport() {}

    static <T> T invoke(String operation, String url, String traceId, Callable<T> call) {
        long started = System.currentTimeMillis();
        try {
            T result = call.call();
            if (result == null) throw new ApplicationException(ErrorCode.AI_SERVICE_INVALID_RESPONSE);
            return result;
        } catch (ApplicationException exception) {
            log.warn("AI call failed, operation={}, url={}, traceId={}, durationMs={}, code={}",
                    operation, url, traceId, System.currentTimeMillis() - started,
                    exception.getErrorCode().name(), exception);
            throw exception;
        } catch (ResourceAccessException exception) {
            ErrorCode code = causedByTimeout(exception)
                    ? ErrorCode.AI_SERVICE_TIMEOUT : ErrorCode.AI_SERVICE_UNAVAILABLE;
            log.warn("AI call failed, operation={}, url={}, traceId={}, durationMs={}, code={}",
                    operation, url, traceId, System.currentTimeMillis() - started, code.name(), exception);
            throw new ApplicationException(code, exception);
        } catch (RestClientException exception) {
            log.warn("AI call failed, operation={}, url={}, traceId={}, durationMs={}, code={}",
                    operation, url, traceId, System.currentTimeMillis() - started,
                    ErrorCode.AI_SERVICE_INVALID_RESPONSE.name(), exception);
            throw new ApplicationException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, exception);
        } catch (Exception exception) {
            throw new ApplicationException(ErrorCode.INTERNAL_ERROR, exception);
        }
    }

    private static boolean causedByTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }
}
