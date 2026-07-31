package vatm.aerosync.api.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(
        prefix = "app.permit-review-test-ui",
        name = "enabled",
        havingValue = "true")
public class PermitReviewTestUiController {

    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "connect-src 'self'",
            "img-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "frame-ancestors 'none'",
            "form-action 'self'");

    @GetMapping(value = {"/permit-review-test", "/permit-review-test/"})
    public ResponseEntity<Resource> index() {
        return resource(
                "permit-review-test-ui/index.html",
                MediaType.TEXT_HTML);
    }

    @GetMapping("/permit-review-test/app.css")
    public ResponseEntity<Resource> stylesheet() {
        return resource(
                "permit-review-test-ui/app.css",
                MediaType.parseMediaType("text/css"));
    }

    @GetMapping("/permit-review-test/app.js")
    public ResponseEntity<Resource> script() {
        return resource(
                "permit-review-test-ui/app.js",
                MediaType.parseMediaType("text/javascript"));
    }

    private ResponseEntity<Resource> resource(String path, MediaType mediaType) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .header("X-Content-Type-Options", "nosniff")
                .body(new ClassPathResource(path));
    }
}
