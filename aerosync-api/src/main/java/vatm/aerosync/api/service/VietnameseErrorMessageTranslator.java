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
    private static final Pattern RULE_ROW_ERROR = Pattern.compile(
            "^(BR-[A-Z0-9-]+): (Schedule )?Row (\\d+): (.+?)(?: \\((\\d+) total validation errors?\\))?$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public String translate(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String value = message.trim();

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
        matcher = RULE_ROW_ERROR.matcher(value);
        if (matcher.matches()) {
            String translatedDetail = translate(matcher.group(4));
            String total = matcher.group(5) == null
                    ? ""
                    : " (tổng cộng " + matcher.group(5) + " lỗi kiểm tra)";
            return matcher.group(1).toUpperCase(Locale.ROOT) + ": Dòng " + matcher.group(3)
                    + ": " + translatedDetail + total;
        }

        String lower = value.toLowerCase(Locale.ROOT);
        String directTranslation = switch (lower) {
            case "callsign is required" -> "Thiếu số hiệu chuyến bay.";
            case "invalid callsign" -> "Số hiệu chuyến bay không hợp lệ.";
            case "invalid from airport" -> "Sân bay khởi hành không hợp lệ.";
            case "invalid to airport" -> "Sân bay đến không hợp lệ.";
            case "invalid icao airport" -> "Mã sân bay ICAO không hợp lệ.";
            case "departure and arrival airports must differ" ->
                    "Sân bay khởi hành và sân bay đến không được trùng nhau.";
            case "dateflight is required" -> "Thiếu ngày bay.";
            case "dateflight is too far in the past" -> "Ngày bay quá xa trong quá khứ.";
            case "dateflight is too far in the future" -> "Ngày bay vượt quá phạm vi cho phép.";
            case "invalid normalized scheduled permit number" -> "Số phép bay sau chuẩn hóa không hợp lệ.";
            case "scheduled permit number must include its four-digit year" ->
                    "Số phép bay phải chứa năm gồm bốn chữ số.";
            case "permit date is required" -> "Thiếu ngày cấp phép bay.";
            case "invalid operator icao code" -> "Mã ICAO của hãng khai thác không hợp lệ.";
            case "permit type and flight type are required" -> "Thiếu loại phép bay hoặc loại chuyến bay.";
            case "at least one schedule row is required" -> "Phải có ít nhất một dòng lịch bay.";
            case "invalid flight number" -> "Số hiệu chuyến bay không hợp lệ.";
            case "invalid schedule airport" -> "Mã sân bay trong lịch bay không hợp lệ.";
            case "invalid utc etd" -> "Giờ khởi hành UTC không hợp lệ.";
            case "invalid utc eta" -> "Giờ đến UTC không hợp lệ.";
            case "invalid schedule effective date range: begin date must not be after end date" ->
                    "Khoảng ngày hiệu lực không hợp lệ: ngày bắt đầu không được sau ngày kết thúc.";
            case "invalid day-of-service flags" -> "Cờ ngày khai thác không hợp lệ.";
            case "no selected operating day falls in the effective date range" ->
                    "Không có ngày khai thác đã chọn nằm trong khoảng ngày hiệu lực.";
            case "airways are required" -> "Thiếu đường bay.";
            case "aircraft mapping is required" -> "Không ánh xạ được loại tàu bay với dữ liệu ATFM.";
            default -> null;
        };
        if (directTranslation != null) {
            return directTranslation;
        }
        if (lower.equals("file does not exist or is not a regular file")) {
            return "File không còn tồn tại hoặc đường dẫn không trỏ tới một file hợp lệ.";
        }
        if (lower.equals("cannot read file size")) {
            return "Không thể đọc kích thước file. Hãy kiểm tra quyền truy cập hoặc xem file có đang bị khóa không.";
        }
        if (lower.equals("cannot read file encoding")) {
            return "Không thể đọc nội dung file để xác định bảng mã. Hãy kiểm tra quyền truy cập hoặc file có bị hỏng không.";
        }
        if (lower.equals("cannot read csv header")) {
            return "Không thể đọc dòng tiêu đề của file CSV.";
        }
        if (lower.equals("file is not valid utf-8")) {
            return "File văn bản không sử dụng bảng mã UTF-8 hợp lệ.";
        }
        if (lower.equals("file exceeds maximum size")) {
            return "File vượt quá dung lượng tối đa được phép xử lý.";
        }
        if (lower.equals("file is empty")) {
            return "File rỗng, không có dữ liệu để xử lý.";
        }
        if (lower.equals("csv has no header row")) {
            return "File CSV không có dòng tiêu đề.";
        }
        if (lower.startsWith("unsupported extension:")) {
            return "Phần mở rộng file không được hỗ trợ: "
                    + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("unsupported word document extension:")) {
            return "Phần mở rộng tài liệu Word không được hỗ trợ: "
                    + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("file is temporarily locked or unreadable:")) {
            return "File đang bị ứng dụng khác khóa hoặc tài khoản chạy AeroSync không có quyền đọc.";
        }
        if (lower.startsWith("failed to parse word permit:")) {
            if (lower.contains("zip") || lower.contains("package")
                    || lower.contains("content type") || lower.contains("invalid header")
                    || lower.contains("ole2") || lower.contains("unexpected end")
                    || lower.contains("truncated")) {
                return "Không thể đọc tài liệu Word vì file bị hỏng hoặc phần mở rộng không đúng với định dạng thực tế.";
            }
            return "Không thể đọc cấu trúc tài liệu Word. Hãy đóng file nếu đang mở, kiểm tra quyền truy cập và thử mở lại file bằng Microsoft Word.";
        }
        if (lower.startsWith("failed to parse csv:")) {
            return "Không thể phân tích file CSV. Hãy kiểm tra bảng mã, dấu phân cách và cấu trúc các dòng dữ liệu.";
        }
        if (lower.startsWith("failed to parse json:")) {
            return "Không thể phân tích file JSON vì nội dung không đúng cấu trúc yêu cầu.";
        }
        if (lower.startsWith("failed to parse xml:")) {
            return "Không thể phân tích file XML vì nội dung không đúng cấu trúc yêu cầu.";
        }
        if (lower.startsWith("failed to parse xlsx:")) {
            return "Không thể đọc file Excel XLSX. File có thể bị hỏng, bị khóa hoặc không phải XLSX hợp lệ.";
        }
        if (lower.equals("json must be an array or contain a flights array")) {
            return "JSON phải là một mảng hoặc phải chứa trường mảng 'flights'.";
        }
        if (lower.startsWith("missing dateflight value for flight")) {
            return "Thiếu ngày bay cho chuyến bay "
                    + value.substring("Missing dateFlight value for flight".length()).trim() + ".";
        }
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
        if (lower.startsWith("invalid effective-to date:")) {
            return "Ngày kết thúc hiệu lực không hợp lệ: " + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("invalid etd:")) {
            return "Giờ khởi hành dự kiến (ETD) không hợp lệ: " + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("invalid eta:")) {
            return "Giờ đến dự kiến (ETA) không hợp lệ: " + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("day-of-service value has no operating day:")) {
            return "Giá trị ngày khai thác không chứa ngày hoạt động hợp lệ: "
                    + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.contains("missing icao code")) {
            return "Thiếu trường ICAO: file có IATA nhưng ICAO đang trống hoặc không hợp lệ.";
        }
        if (lower.contains("missing iata code")) {
            return "Thiếu trường IATA: file có ICAO nhưng IATA đang trống hoặc không hợp lệ.";
        }
        if (lower.contains("iata and icao fields are present but both values are blank or invalid")) {
            return "Các trường IATA và ICAO đều đang trống hoặc không hợp lệ.";
        }
        if (lower.contains("operator code field is present but its value is blank or invalid")) {
            return "Trường mã hãng khai thác đang trống hoặc không hợp lệ.";
        }
        if (lower.equals("aircraft type is required")) {
            return "Thiếu loại tàu bay trong lịch bay và phần thông tin tàu bay.";
        }
        if (lower.startsWith("ambiguous word permit format profiles:")) {
            return "Tài liệu khớp với nhiều format YAML nên hệ thống không thể tự chọn: "
                    + value.substring(value.indexOf(':') + 1).trim() + ".";
        }
        if (lower.startsWith("schedule table not found for profile")) {
            return "Không tìm thấy bảng lịch bay có đủ các cột bắt buộc theo format "
                    + value.substring("Schedule table not found for profile".length()).trim() + ".";
        }
        if (lower.startsWith("no schedule rows found for profile")) {
            return "Không tìm thấy dòng lịch bay hợp lệ theo format "
                    + value.substring("No schedule rows found for profile".length()).trim() + ".";
        }
        if (lower.contains("airways table row") || lower.contains("airways table is present")) {
            return "Thiếu đường bay trong bảng Airways; vui lòng điền đủ sector và route.";
        }
        if (lower.contains("invalid schedule effective date range")) {
            return "Sai khoảng ngày hiệu lực: ngày bắt đầu không được lớn hơn ngày kết thúc.";
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
        if (lower.equals("atfm writes are disabled")) {
            return "Chức năng ghi dữ liệu vào ATFM đang bị tắt trong cấu hình.";
        }
        if (lower.startsWith("archived attachment is no longer available:")) {
            return "File lưu trữ không còn tồn tại nên không thể xử lý lại.";
        }
        if (lower.startsWith("no file records for job:")) {
            return "Không tìm thấy bản ghi file của tác vụ đồng bộ.";
        }
        if (lower.startsWith("sync job not found:")) {
            return "Không tìm thấy tác vụ đồng bộ yêu cầu.";
        }
        if (lower.startsWith("archive failed:")) {
            return "Xử lý xong nhưng không thể chuyển file vào thư mục lưu trữ.";
        }
        if (lower.contains("could not move message")
                || lower.contains("failed to move message")) {
            return "Không thể chuyển email sang thư mục đích trên máy chủ thư.";
        }
        if (lower.contains("could not send acknowledgement")
                || lower.contains("failed to send acknowledgement")) {
            return "Không thể gửi email thông báo kết quả xử lý.";
        }
        if (lower.contains("failed to store email attachment")) {
            return "Không thể lưu file đính kèm từ email vào thư mục tiếp nhận.";
        }
        if (lower.contains("access is denied") || lower.contains("accessdeniedexception")) {
            return "Không có quyền truy cập file hoặc thư mục cần xử lý.";
        }
        if (lower.contains("being used by another process")) {
            return "File đang được ứng dụng khác sử dụng nên chưa thể đọc.";
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

        if (containsVietnamese(value) && !looksLikeEnglishTechnicalMessage(value)) {
            return value;
        }

        return "Đã xảy ra lỗi trong quá trình xử lý. Vui lòng xem nhật ký kỹ thuật để biết chi tiết.";
    }

    private boolean looksLikeEnglishTechnicalMessage(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("failed")
                || lower.contains("cannot ")
                || lower.contains("invalid ")
                || lower.contains("missing ")
                || lower.contains("not found")
                || lower.contains("unsupported ")
                || lower.contains("error")
                || lower.contains("exception");
    }

    private boolean containsVietnamese(String value) {
        return value.matches(".*[ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ].*");
    }
}
