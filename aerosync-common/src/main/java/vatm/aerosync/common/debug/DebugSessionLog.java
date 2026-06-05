package vatm.aerosync.common.debug;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class DebugSessionLog {

    private static final Path LOG_PATH = Path.of("debug-1208ff.log");
    private static final String SESSION_ID = "1208ff";

    private DebugSessionLog() {
    }

    public static void log(String hypothesisId, String location, String message, Map<String, ?> data) {
        // #region agent log
        try {
            String dataJson = data.entrySet().stream()
                    .map(e -> "\"" + escape(e.getKey()) + "\":" + toJsonValue(e.getValue()))
                    .collect(Collectors.joining(","));
            String line = "{\"sessionId\":\"" + SESSION_ID + "\",\"hypothesisId\":\"" + escape(hypothesisId)
                    + "\",\"location\":\"" + escape(location) + "\",\"message\":\"" + escape(message)
                    + "\",\"data\":{" + dataJson + "},\"timestamp\":" + System.currentTimeMillis() + "}\n";
            Files.writeString(LOG_PATH, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
        // #endregion
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private static String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
