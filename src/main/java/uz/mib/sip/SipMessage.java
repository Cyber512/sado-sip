package uz.mib.sip;

import java.util.*;

public class SipMessage {
    private String method;
    private String requestUri;
    private int statusCode;
    private String reasonPhrase;
    private boolean request;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private String body = "";

    public static SipMessage parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        SipMessage msg = new SipMessage();

        int bodyIdx = raw.indexOf("\r\n\r\n");
        String headerPart = bodyIdx >= 0 ? raw.substring(0, bodyIdx) : raw;
        msg.body = bodyIdx >= 0 ? raw.substring(bodyIdx + 4) : "";

        String[] lines = headerPart.split("\r\n", -1);
        if (lines.length == 0) return null;

        String first = lines[0];
        if (first.startsWith("SIP/2.0 ")) {
            msg.request = false;
            String rest = first.substring(8);
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                msg.statusCode   = Integer.parseInt(rest.substring(0, sp).trim());
                msg.reasonPhrase = rest.substring(sp + 1).trim();
            } else {
                msg.statusCode   = Integer.parseInt(rest.trim());
                msg.reasonPhrase = "";
            }
        } else {
            msg.request = true;
            String[] p = first.split(" ", 3);
            msg.method     = p.length > 0 ? p[0] : "";
            msg.requestUri = p.length > 1 ? p[1] : "";
        }

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name  = compact(line.substring(0, colon).trim());
            String value = line.substring(colon + 1).trim();
            msg.headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return msg;
    }

    private static String compact(String h) {
        return switch (h) {
            case "v" -> "via";
            case "f" -> "from";
            case "t" -> "to";
            case "i" -> "call-id";
            case "m" -> "contact";
            case "l" -> "content-length";
            case "c" -> "content-type";
            default  -> h.toLowerCase();
        };
    }

    public boolean isRequest()       { return request; }
    public String  getMethod()       { return method; }
    public String  getRequestUri()   { return requestUri; }
    public int     getStatusCode()   { return statusCode; }
    public String  getReasonPhrase() { return reasonPhrase; }
    public String  getBody()         { return body; }

    public String getHeader(String name) {
        List<String> v = headers.get(name.toLowerCase());
        return (v != null && !v.isEmpty()) ? v.get(0) : null;
    }
}
