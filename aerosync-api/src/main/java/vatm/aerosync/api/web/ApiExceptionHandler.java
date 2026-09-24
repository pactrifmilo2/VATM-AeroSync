package vatm.aerosync.api.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import vatm.aerosync.api.service.VietnameseErrorMessageTranslator;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final VietnameseErrorMessageTranslator errorMessageTranslator =
            new VietnameseErrorMessageTranslator();

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, errorMessageTranslator.translate(ex.getMessage()));
        detail.setTitle("Không tìm thấy dữ liệu");
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Dữ liệu yêu cầu không hợp lệ");
        detail.setDetail(errorMessageTranslator.translate(
                ex.getBindingResult().getAllErrors().getFirst().getDefaultMessage()));
        return detail;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, errorMessageTranslator.translate(ex.getMessage()));
        detail.setTitle("Trạng thái dữ liệu xung đột");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, errorMessageTranslator.translate(ex.getMessage()));
        detail.setTitle("Yêu cầu không hợp lệ");
        return detail;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                errorMessageTranslator.translate("Invalid value for request parameter: " + ex.getName()));
        detail.setTitle("YÃªu cáº§u khÃ´ng há»£p lá»‡");
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled API error", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Máy chủ gặp lỗi khi xử lý yêu cầu. Vui lòng thử lại hoặc kiểm tra nhật ký hệ thống.");
        detail.setTitle("Lỗi máy chủ");
        return detail;
    }
}
