package vatm.aerosync.ingest.support;

public final class PriorityDetector {

    private PriorityDetector() {
    }

    public static boolean isPriority(String fileName, String subject) {
        if (fileName != null && containsPriorityToken(fileName)) {
            return true;
        }
        return subject != null && containsPriorityToken(subject);
    }

    private static boolean containsPriorityToken(String value) {
        String upper = value.toUpperCase();
        return upper.contains("URGENT") || upper.contains("VIP");
    }
}
