package vatm.aerosync.worker.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts technical errors to Vietnamese before persisting ERROR_MESSAGE. */
public final class VietnameseStoredErrorMessage {

    private static final Pattern ORACLE_ERROR = Pattern.compile("(ORA-\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW_NUMBER = Pattern.compile("(?i)(?:schedule )?row\\s+(\\d+)");

    private VietnameseStoredErrorMessage() {
    }

    public static String translate(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String value = message.trim();
        String lower = value.toLowerCase(Locale.ROOT);

        if (lower.contains("đã xảy ra lỗi kỹ thuật trong quá trình xử lý file")
                || lower.contains("đã xảy ra lỗi trong quá trình xử lý")) {
            return "Bản ghi lỗi cũ không lưu được nguyên nhân cụ thể. Hãy gửi lại file để hệ thống kiểm tra và ghi nhận lỗi đầy đủ.";
        }
        if (isMissingWordFormat(lower)) {
            return "CẦN THÊM FORMAT YAML: File Word chưa có format YAML phù hợp.";
        }
        if (lower.contains("ambiguous word permit format")) {
            return "File Word khớp với nhiều format YAML nên hệ thống không thể tự chọn.";
        }
        if (lower.contains("failed to parse word") || lower.contains("invalid header signature")
                || lower.contains("zip end header") || lower.contains("not a valid ole2")) {
            return "Không thể đọc file Word vì file bị hỏng hoặc phần mở rộng không đúng với định dạng thực tế.";
        }
        if (lower.contains("temporarily locked") || lower.contains("being used by another process")) {
            return "File đang được ứng dụng khác sử dụng nên chưa thể đọc.";
        }
        if (lower.contains("access is denied") || lower.contains("accessdeniedexception")) {
            return "Không có quyền truy cập file hoặc thư mục cần xử lý.";
        }
        if (lower.contains("file does not exist") || lower.contains("nosuchfileexception")) {
            return "File không còn tồn tại hoặc đường dẫn file không hợp lệ.";
        }
        if (lower.contains("file is empty")) {
            return "File rỗng, không có dữ liệu để xử lý.";
        }
        if (lower.contains("file exceeds maximum size")) {
            return "File vượt quá dung lượng tối đa được phép xử lý.";
        }
        if (lower.contains("unsupported extension")) {
            return "Phần mở rộng của file không được hệ thống hỗ trợ.";
        }
        if (lower.contains("failed to parse csv")) {
            return "Không thể phân tích file CSV vì nội dung không đúng định dạng yêu cầu.";
        }
        if (lower.contains("failed to parse json")) {
            return "Không thể phân tích file JSON vì nội dung không đúng định dạng yêu cầu.";
        }
        if (lower.contains("failed to parse xml")) {
            return "Không thể phân tích file XML vì nội dung không đúng định dạng yêu cầu.";
        }
        if (lower.contains("failed to parse xlsx")) {
            return "Không thể đọc file Excel vì file bị hỏng hoặc không đúng định dạng XLSX.";
        }
        if (lower.contains("schedule table not found")) {
            return "Không tìm thấy bảng lịch bay phù hợp với format YAML đã nhận diện.";
        }
        if (lower.contains("no schedule rows") || lower.contains("no data rows")) {
            return "Không tìm thấy dòng lịch bay hợp lệ trong file.";
        }
        if (lower.contains("permit number not found")) {
            return "Không tìm thấy số phép bay trong file.";
        }
        if (lower.contains("permit date not found") || lower.contains("invalid permit date")) {
            return "Không tìm thấy hoặc ngày cấp phép bay không hợp lệ.";
        }
        if (lower.contains("invalid effective-from date")) {
            return "Ngày bắt đầu hiệu lực không hợp lệ: " + detailAfterColon(value) + ".";
        }
        if (lower.contains("invalid effective-to date")) {
            return "Ngày kết thúc hiệu lực không hợp lệ: " + detailAfterColon(value) + ".";
        }
        if (lower.contains("carrier icao") || lower.contains("missing icao code")
                || lower.contains("oper_icao")) {
            return "Không xác định được mã ICAO của hãng khai thác.";
        }
        if (lower.contains("missing iata code")) {
            return "Không xác định được mã IATA của hãng khai thác.";
        }
        if (lower.contains("aircraft") || lower.contains("craft id")) {
            return withRow(value, "Loại tàu bay không hợp lệ hoặc không có dữ liệu đối chiếu trong ATFM.");
        }
        if (lower.contains("airways") || lower.contains("route not found")) {
            return withRow(value, "Không tìm thấy đường bay hợp lệ hoặc dữ liệu đường bay đối chiếu trong ATFM.");
        }
        if (lower.contains("airport") || lower.contains("m_aero")) {
            return withRow(value, "Mã sân bay không hợp lệ hoặc không có dữ liệu đối chiếu trong ATFM.");
        }
        if (lower.contains("dateflight") || lower.contains("effective date")
                || lower.contains("invalid etd") || lower.contains("invalid eta")) {
            return withRow(value, "Ngày hoặc giờ khai thác không hợp lệ.");
        }
        if (lower.contains("callsign") || lower.contains("flight number")) {
            return withRow(value, "Số hiệu chuyến bay không hợp lệ.");
        }
        if (lower.contains("atfm writes are disabled")) {
            return "Chức năng ghi dữ liệu vào ATFM đang bị tắt trong cấu hình.";
        }
        if (lower.contains("manual review")) {
            return "Giấy phép bay cần được kiểm tra thủ công trước khi ghi vào ATFM.";
        }
        if (lower.contains("revision") || lower.contains("original atfm permit")) {
            return "Không thể tự động xử lý bản sửa đổi vì không tìm thấy hoặc không xác định được giấy phép bay gốc trong ATFM.";
        }
        if (lower.contains("another worker is processing")) {
            return "Giấy phép bay này đang được một tiến trình khác xử lý.";
        }
        if (lower.contains("atfm lookup not found")) {
            return "Không tìm thấy dữ liệu đối chiếu cần thiết trong ATFM.";
        }
        if (lower.startsWith("archive failed") || lower.contains("archive unavailable")) {
            return "Đã xử lý dữ liệu nhưng không thể chuyển file vào thư mục lưu trữ.";
        }
        Matcher oracle = ORACLE_ERROR.matcher(value);
        if (oracle.find()) {
            return "Không thể ghi dữ liệu vào ATFM. Mã lỗi Oracle: "
                    + oracle.group(1).toUpperCase(Locale.ROOT) + ".";
        }
        if (lower.contains("duplicate") || lower.contains("already exists")) {
            return "Dữ liệu đã tồn tại nên file được bỏ qua để tránh nhập trùng.";
        }
        if (lower.contains("sync job not found")) {
            return "Không tìm thấy tác vụ xử lý tương ứng trong cơ sở dữ liệu.";
        }
        if (lower.contains("syncjobid") || lower.contains("sync job id")) {
            return "Bản tin xử lý file thiếu mã tác vụ liên kết.";
        }
        if (lower.contains("nullpointerexception") || lower.contains("cannot invoke")
                || lower.contains("must not be null")) {
            return "Dữ liệu xử lý bị thiếu một trường bắt buộc nên không thể hoàn tất.";
        }
        if (lower.contains("parser") || lower.contains("parse")) {
            return "Bộ phân tích không thể đọc cấu trúc nội dung của file.";
        }
        if (containsVietnamese(value) && !looksEnglish(lower)) {
            return value;
        }

        return "Không thể hoàn tất xử lý file vì nguyên nhân chưa được phân loại. Mã tham chiếu: "
                + Integer.toUnsignedString(value.hashCode(), 16).toUpperCase(Locale.ROOT) + ".";
    }

    private static boolean isMissingWordFormat(String lower) {
        return lower.contains("unsupported word permit format")
                || lower.contains("no format profile matched")
                || lower.contains("không hỗ trợ định dạng file word này vì chưa có format yaml phù hợp")
                || lower.contains("không hỗ trợ định dạng giấy phép bay word này; không có format yaml nào phù hợp")
                || lower.contains("file word chưa có format yaml phù hợp");
    }

    private static String withRow(String value, String detail) {
        Matcher matcher = ROW_NUMBER.matcher(value);
        return matcher.find() ? "Dòng lịch bay " + matcher.group(1) + ": " + detail : detail;
    }

    private static String detailAfterColon(String value) {
        int colon = value.lastIndexOf(':');
        return colon >= 0 && colon + 1 < value.length()
                ? value.substring(colon + 1).trim()
                : value;
    }

    private static boolean containsVietnamese(String value) {
        return value.matches(".*[ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ].*");
    }

    private static boolean looksEnglish(String lower) {
        return lower.contains("failed") || lower.contains("cannot ") || lower.contains("invalid ")
                || lower.contains("missing ") || lower.contains("not found")
                || lower.contains("unsupported ") || lower.contains("exception");
    }
}
