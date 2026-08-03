using System.Net;
using System.Text.Json;

namespace AeroSync.UI.Services;

public static class VietnameseErrorMessage
{
    public static string FromException(Exception exception, string action)
    {
        var error = exception is AggregateException aggregate
            ? aggregate.GetBaseException()
            : exception;

        return error switch
        {
            TaskCanceledException => $"{action} quá thời gian chờ. Vui lòng kiểm tra kết nối và thử lại.",
            HttpRequestException http when http.StatusCode.HasValue
                                           && http.Message.Contains("không thành công", StringComparison.OrdinalIgnoreCase) =>
                http.Message,
            HttpRequestException http when http.StatusCode.HasValue =>
                $"{action} không thành công. {ForStatus(http.StatusCode.Value)}",
            HttpRequestException => $"{action} không thành công do không kết nối được tới máy chủ.",
            JsonException => $"{action} không thành công vì dữ liệu máy chủ trả về không đúng định dạng.",
            _ => $"{action} không thành công. Vui lòng thử lại hoặc kiểm tra nhật ký hệ thống."
        };
    }

    public static async Task ThrowIfFailedAsync(
        HttpResponseMessage response,
        string action,
        CancellationToken cancellationToken)
    {
        if (response.IsSuccessStatusCode)
        {
            return;
        }

        var rawDetail = await response.Content.ReadAsStringAsync(cancellationToken);
        var detail = TranslateServerDetail(ExtractServerDetail(rawDetail));
        var message = $"{action} không thành công. {ForStatus(response.StatusCode)}";
        if (!string.IsNullOrWhiteSpace(detail))
        {
            message += $" Chi tiết: {detail}";
        }

        throw new HttpRequestException(message, null, response.StatusCode);
    }

    private static string ForStatus(HttpStatusCode statusCode) => statusCode switch
    {
        HttpStatusCode.BadRequest => "Yêu cầu gửi lên không hợp lệ.",
        HttpStatusCode.Unauthorized => "Phiên đăng nhập đã hết hạn hoặc chưa được xác thực.",
        HttpStatusCode.Forbidden => "Bạn không có quyền thực hiện thao tác này.",
        HttpStatusCode.NotFound => "Không tìm thấy dữ liệu cần xử lý.",
        HttpStatusCode.Conflict => "Dữ liệu đang ở trạng thái xung đột và chưa thể xử lý lại.",
        HttpStatusCode.TooManyRequests => "Hệ thống đang nhận quá nhiều yêu cầu. Vui lòng thử lại sau.",
        HttpStatusCode.InternalServerError => "Máy chủ gặp lỗi nội bộ.",
        HttpStatusCode.BadGateway => "Máy chủ trung gian không nhận được phản hồi hợp lệ.",
        HttpStatusCode.ServiceUnavailable => "Dịch vụ hiện không khả dụng.",
        HttpStatusCode.GatewayTimeout => "Máy chủ xử lý quá thời gian chờ.",
        _ => $"Máy chủ trả về mã lỗi HTTP {(int)statusCode}."
    };

    private static string ExtractServerDetail(string rawDetail)
    {
        if (string.IsNullOrWhiteSpace(rawDetail))
        {
            return "";
        }

        try
        {
            using var document = JsonDocument.Parse(rawDetail);
            var root = document.RootElement;
            foreach (var propertyName in new[] { "message", "detail", "error", "title" })
            {
                if (root.ValueKind == JsonValueKind.Object
                    && root.TryGetProperty(propertyName, out var value)
                    && value.ValueKind == JsonValueKind.String)
                {
                    return value.GetString() ?? "";
                }
            }
        }
        catch (JsonException)
        {
            // Phản hồi dạng văn bản thuần sẽ được xử lý ở dưới.
        }

        return rawDetail.Trim().Trim('"');
    }

    private static string TranslateServerDetail(string detail)
    {
        if (string.IsNullOrWhiteSpace(detail))
        {
            return "";
        }

        var normalized = detail.ToLowerInvariant();
        if (normalized.Contains("replay") && normalized.Contains("disabled"))
        {
            return "Chức năng phát lại đang bị tắt trong cấu hình máy chủ.";
        }
        if (normalized.Contains("email resend") && normalized.Contains("disabled"))
        {
            return "Chức năng gửi lại email đang bị tắt trong cấu hình máy chủ.";
        }
        if (normalized.Contains("gmail smtp credentials") || normalized.Contains("app_email_resend_username"))
        {
            return "Chưa cấu hình tài khoản Gmail và App Password dùng để gửi lại email.";
        }
        if (normalized.Contains("gmail smtp authentication failed")
            || normalized.Contains("username and password not accepted")
            || normalized.Contains("badcredentials"))
        {
            return "Gmail không chấp nhận thông tin đăng nhập. Hãy bật xác minh 2 bước và dùng App Password 16 ký tự, không dùng mật khẩu Gmail thông thường.";
        }
        if (normalized.Contains("gmail smtp could not send"))
        {
            return "Gmail từ chối gửi email. Vui lòng kiểm tra tài khoản, App Password và kết nối mạng.";
        }
        if (normalized.Contains("no archived attachment"))
        {
            return "Không còn file đính kèm trong kho lưu trữ để gửi lại.";
        }
        if (normalized.Contains("not found"))
        {
            return "Không tìm thấy bản ghi tương ứng.";
        }
        if (normalized.Contains("already") && normalized.Contains("processing"))
        {
            return "File này đang được xử lý.";
        }
        if (normalized.Contains("duplicate"))
        {
            return "File bị trùng lặp nên được hệ thống bảo vệ và bỏ qua.";
        }
        if (normalized.Contains("confirm") && normalized.Contains("permit"))
        {
            return "Mã phép bay xác nhận không khớp với dữ liệu hiện tại.";
        }
        if (normalized.Contains("lastuser") || normalized.Contains("aerosync-owned"))
        {
            return "Dữ liệu không thuộc quyền quản lý của AEROSYNC nên không được phép xóa.";
        }

        // Chỉ giữ nguyên thông báo đã là tiếng Việt; không đưa lỗi kỹ thuật tiếng Anh/HTML lên giao diện.
        const string vietnameseCharacters = "ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ";
        return detail.Any(character => vietnameseCharacters.Contains(char.ToLowerInvariant(character)))
            ? detail
            : "Máy chủ từ chối yêu cầu. Hãy xem nhật ký hệ thống để biết chi tiết kỹ thuật.";
    }
}
