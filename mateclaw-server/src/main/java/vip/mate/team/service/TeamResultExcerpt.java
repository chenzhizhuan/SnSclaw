package vip.mate.team.service;

/** Bounded model-facing excerpts; persisted worker messages remain authoritative. */
public final class TeamResultExcerpt {

    private TeamResultExcerpt() {
    }

    public static String outputReference(Long taskId) {
        return "team_tasks(action=\"output\", taskId=\"" + taskId + "\")";
    }

    /** Bound descriptive labels separately so names cannot consume an evidence budget. */
    public static String label(String text, int maxChars) {
        if (text == null) return "";
        if (maxChars < 1) return "";
        int end = Math.min(text.length(), maxChars);
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end).replace('\n', ' ').replace('\r', ' ');
    }

    /** Keeps both the opening context and the final outcome, including the marker in the cap. */
    public static String render(String text, int maxChars, Long taskId) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        String marker = "\n[... omitted; full output: " + outputReference(taskId)
                + ". Do not infer omitted content.]\n";
        int available = maxChars - marker.length();
        if (available < 2) {
            throw new IllegalArgumentException("Excerpt budget cannot hold the output reference");
        }
        int headEnd = available * 3 / 4;
        int tailStart = text.length() - (available - headEnd);
        if (headEnd > 0 && Character.isHighSurrogate(text.charAt(headEnd - 1))
                && Character.isLowSurrogate(text.charAt(headEnd))) {
            headEnd--;
        }
        if (tailStart > 0 && Character.isLowSurrogate(text.charAt(tailStart))
                && Character.isHighSurrogate(text.charAt(tailStart - 1))) {
            tailStart++;
        }
        return text.substring(0, headEnd) + marker + text.substring(tailStart);
    }
}
