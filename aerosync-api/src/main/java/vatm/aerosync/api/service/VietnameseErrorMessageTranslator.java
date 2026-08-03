package vatm.aerosync.api.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VietnameseErrorMessageTranslator {

    private static final Pattern REVISION_DIFFERENT = Pattern.compile(
            "PERMIT-REVISION-REVIEW: Permit (.+?) was previously imported with different schedule data",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REVISION_AUTOMATIC = Pattern.compile(
            "PERMIT-REVISION-REVIEW: Permit (.+?) is a revision and was not written automatically",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OPERATOR_REVIEW = Pattern.compile(
            "PERMIT-REVISION-REVIEW: Permit (.+?) requires operator review and was not written automatically",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PROFILE_SUFFIX = Pattern.compile("(?i)^(.+?) for profile (.+)$", Pattern.DOTALL);
    private static final Pattern SCHEDULE_AIRPORT = Pattern.compile(
            "BR-SCHEDULE-AIRPORT: Schedule row (\\d+): Invalid schedule airport \\((\\d+) total validation errors?\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AIRWAYS_REQUIRED = Pattern.compile(
            "BR-AIRWAYS: Schedule row (\\d+): Airways are required \\((\\d+) total validation errors?\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AIRCRAFT_AMBIGUOUS = Pattern.compile(
            "BR-AIRCRAFT-AMBIGUOUS: Schedule row (\\d+): Ambiguous aircraft type (.+?); matching craft IDs: (.+?) \\((\\d+) aircraft resolution errors?\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AIRCRAFT_NOT_FOUND = Pattern.compile(
            "BR-AIRCRAFT-NOT-FOUND: Schedule row (\\d+): Unsupported aircraft type: (.+?) \\(tried: (.+?)\\) \\((\\d+) aircraft resolution errors?\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public String translate(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String value = message.trim();
        if (containsVietnamese(value)) {
            return value;
        }

        Matcher matcher = REVISION_DIFFERENT.matcher(value);
        if (matcher.matches()) {
            return "PERMIT-REVISION-REVIEW: Phép bay " + matcher.group(1)
                    + " đã được nhập trước đó nhưng có dữ liệu lịch bay khác.";
        }
        matcher = REVISION_AUTOMATIC.matcher(value);
        if (matcher.matches()) {
            return "PERMIT-REVISION-REVIEW: Phép bay " + matcher.group(1)
                    + " là bản sửa đổi nên không được tự động ghi vào ATFM.";
        }
        matcher = OPERATOR_REVIEW.matcher(value);
        if (matcher.matches()) {
            return "PERMIT-REVISION-REVIEW: Phép bay " + matcher.group(1)
                    + " cần người vận hành kiểm tra nên không được tự động ghi vào ATFM.";
        }
        matcher = SCHEDULE_AIRPORT.matcher(value);
        if (matcher.matches()) {
            return "BR-SCHEDULE-AIRPORT: Dòng lịch bay " + matcher.group(1)
                    + " có mã sân bay không hợp lệ (tổng cộng " + matcher.group(2) + " lỗi kiểm tra).";
        }
        matcher = AIRWAYS_REQUIRED.matcher(value);
        if (matcher.matches()) {
            return "BR-AIRWAYS: Dòng lịch bay " + matcher.group(1)
                    + " thiếu đường bay (tổng cộng " + matcher.group(2) + " lỗi kiểm tra).";
        }
        matcher = AIRCRAFT_AMBIGUOUS.matcher(value);
        if (matcher.matches()) {
            return "BR-AIRCRAFT-AMBIGUOUS: Dòng lịch bay " + matcher.group(1)
                    + " có loại tàu bay không xác định duy nhất " + matcher.group(2)
                    + "; các mã CRAFT phù hợp: " + matcher.group(3)
                    + " (tổng cộng " + matcher.group(4) + " lỗi nhận diện tàu bay).";
        }
        matcher = AIRCRAFT_NOT_FOUND.matcher(value);
        if (matcher.matches()) {
            return "BR-AIRCRAFT-NOT-FOUND: Dòng lịch bay " + matcher.group(1)
                    + " có loại tàu bay không được hỗ trợ: " + matcher.group(2)
                    + " (đã thử: " + matcher.group(3) + "; tổng cộng "
                    + matcher.group(4) + " lỗi nhận diện tàu bay).";
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("unsupported word permit format; no format profile matched")) {
            return "Không hỗ trợ định dạng giấy phép bay Word này; không có format YAML nào phù hợp.";
        }
        if (lower.startsWith("unsupported word permit format; best profile candidates:")) {
            return "Không tìm thấy format YAML phù hợp. Các format gần nhất: "
                    + value.substring(value.indexOf(':') + 1).trim();
        }
        if (lower.equals("no data rows found")) {
            return "Không tìm thấy dòng dữ liệu nào trong bảng.";
        }
        if (lower.equals("permit date not found")) {
            return "Không tìm thấy ngày cấp phép bay.";
        }
        if (lower.equals("carrier icao code could not be inferred from the schedule")
                || lower.equals("carrier icao code not found")) {
            return "Không xác định được mã ICAO của hãng bay từ lịch bay.";
        }
        if (lower.startsWith("missing required column:")) {
            return "Thiếu cột bắt buộc: " + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("unsupported aircraft type:")) {
            return "Loại tàu bay không được hỗ trợ: " + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("invalid effective-from date:")) {
            return "Ngày bắt đầu hiệu lực không hợp lệ: " + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("invalid permit date:")) {
            return "Ngày cấp phép bay không hợp lệ: " + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("br-atfm-reference: atfm lookup not found:")) {
            return "BR-ATFM-REFERENCE: Không tìm thấy dữ liệu đối chiếu trong ATFM: "
                    + value.substring(value.indexOf("not found:") + 10).trim() + ".";
        }
        if (lower.startsWith("br-atfm-reference: atfm route not found:")) {
            return "BR-ATFM-REFERENCE: Không tìm thấy đường bay trong ATFM: "
                    + value.substring(value.indexOf("not found:") + 10).trim() + ".";
        }
        if (lower.startsWith("br-atfm-reference: ambiguous atfm routes")) {
            return "BR-ATFM-REFERENCE: Có nhiều đường bay ATFM phù hợp, không thể tự chọn: "
                    + value.substring("BR-ATFM-REFERENCE: Ambiguous ATFM routes".length()).trim() + ".";
        }
        if (lower.startsWith("br-atfm-reference: ambiguous m_aero airport mapping")) {
            return "BR-ATFM-REFERENCE: Có nhiều ánh xạ sân bay trong M_AERO, không thể tự chọn: "
                    + value.substring("BR-ATFM-REFERENCE: Ambiguous M_AERO airport mapping".length()).trim() + ".";
        }
        if (lower.startsWith("failed to insert scheduled permit in atfm:")) {
            String oracleCode = value.contains("ORA-")
                    ? value.substring(value.indexOf("ORA-")).replaceAll("(?s)https://docs\\.oracle\\.com/.*", "").trim()
                    : "";
            return "Không thể ghi giấy phép bay vào ATFM. Lỗi Oracle: " + oracleCode;
        }
        if (lower.equals("lettuceconnectionfactory is stopping")) {
            return "Kết nối Redis đang dừng; vui lòng thử lại sau khi dịch vụ khởi động xong.";
        }

        matcher = PROFILE_SUFFIX.matcher(value);
        if (matcher.matches()) {
            String prefix = matcher.group(1).toLowerCase(Locale.ROOT);
            String profile = matcher.group(2);
            if (prefix.equals("permit number not found")) {
                return "Không tìm thấy số phép bay theo format " + profile + ".";
            }
            if (prefix.equals("schedule table not found")) {
                return "Không tìm thấy bảng lịch bay theo format " + profile + ".";
            }
            if (prefix.equals("airways table not found")) {
                return "Không tìm thấy bảng đường bay theo format " + profile + ".";
            }
            if (prefix.equals("no schedule rows found")) {
                return "Không tìm thấy dòng lịch bay nào theo format " + profile + ".";
            }
        }

        return "Đã xảy ra lỗi trong quá trình xử lý. Vui lòng xem nhật ký kỹ thuật để biết chi tiết.";
    }

    private boolean containsVietnamese(String value) {
        return value.matches(".*[ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ].*");
    }
}
