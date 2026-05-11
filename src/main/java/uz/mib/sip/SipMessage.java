package uz.mib.sip;

import java.util.*;

public class SipMessage {
    public final String raw;
    public final boolean isResponse;
    public final int statusCode;
    public final String reasonPhrase;
    public final String method;
    public final String requestUri;
    private final Map<String, String> headers = new LinkedHashMap<>();
    public final String body;

    private SipMessage(String raw, boolean isResponse, int statusCode, String reasonPhrase,
                       String method, String requestUri, Map<String, String> hdrs, String body) {
        this.raw = raw;
        this.isResponse = isResponse;
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.method = method;
        this.requestUri = requestUri;
        this.headers.putAll(hdrs);
        this.body = body;
    }

    public static SipMessage parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        // Normalize line endings
        String normalized = raw.replace("\r\n", "\n").replace("\r", "\n");
        int headerBodySep = normalized.indexOf("\n\n");
        String headerPart;
        String body = "";
        if (headerBodySep >= 0) {
            headerPart = normalized.substring(0, headerBodySep);
            body = normalized.substring(headerBodySep + 2);
        } else {
            headerPart = normalized;
        }

        String[] lines = headerPart.split("\n");
        if (lines.length == 0) return null;

        String firstLine = lines[0].trim();
        boolean isResponse = firstLine.startsWith("SIP/2.0");
        int statusCode = 0;
        String reasonPhrase = "";
        String method = "";
        String requestUri = "";

        if (isResponse) {
            String[] parts = firstLine.split(" ", 3);
            if (parts.length >= 2) {
                try { statusCode = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
            }
            if (parts.length >= 3) reasonPhrase = parts[2].trim();
        } else {
            String[] parts = firstLine.split(" ", 3);
            if (parts.length >= 1) method = parts[0].trim();
            if (parts.length >= 2) requestUri = parts[1].trim();
        }

        Map<String, String> hdrs = new LinkedHashMap<>();
        // Parse headers - handle folded headers
        StringBuilder currentHeader = null;
        String currentKey = null;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;

            // Folded header continuation
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && currentKey != null) {
                if (currentHeader != null) {
                    currentHeader.append(" ").append(line.trim());
                }
                continue;
            }

            // Save previous header
            if (currentKey != null && currentHeader != null) {
                hdrs.merge(currentKey, currentHeader.toString(), (a, b) -> a + "," + b);
            }

            int colon = line.indexOf(':');
            if (colon < 0) {
                currentKey = null;
                currentHeader = null;
                continue;
            }

            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            // Expand compact header names
            key = expandCompactHeader(key);
            currentKey = key;
            currentHeader = new StringBuilder(value);
        }

        // Save last header
        if (currentKey != null && currentHeader != null) {
            hdrs.merge(currentKey, currentHeader.toString(), (a, b) -> a + "," + b);
        }

        return new SipMessage(raw, isResponse, statusCode, reasonPhrase, method, requestUri, hdrs, body);
    }

    private static String expandCompactHeader(String key) {
        switch (key) {
            case "v": return "via";
            case "f": return "from";
            case "t": return "to";
            case "m": return "contact";
            case "c": return "content-type";
            case "i": return "call-id";
            case "l": return "content-length";
            case "e": return "content-encoding";
            case "s": return "subject";
            case "o": return "event";
            case "u": return "allow-events";
            case "r": return "refer-to";
            case "b": return "referred-by";
            case "k": return "supported";
            default: return key;
        }
    }

    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    public String via() { return getHeader("via"); }
    public String from() { return getHeader("from"); }
    public String to() { return getHeader("to"); }
    public String callId() { return getHeader("call-id"); }
    public String cseq() { return getHeader("cseq"); }
    public String contact() { return getHeader("contact"); }
    public String contentType() { return getHeader("content-type"); }
    public String contentLength() { return getHeader("content-length"); }
    public String wwwAuthenticate() { return getHeader("www-authenticate"); }
    public String proxyAuthenticate() { return getHeader("proxy-authenticate"); }

    public String extractTag(String header) {
        if (header == null) return null;
        int idx = header.toLowerCase().indexOf(";tag=");
        if (idx < 0) return null;
        String rest = header.substring(idx + 5);
        int semi = rest.indexOf(';');
        return semi < 0 ? rest.trim() : rest.substring(0, semi).trim();
    }

    public String extractUser(String uri) {
        if (uri == null) return null;
        // Handle angle brackets
        int lt = uri.indexOf('<');
        int gt = uri.indexOf('>');
        String inner = (lt >= 0 && gt > lt) ? uri.substring(lt + 1, gt) : uri;
        // strip params
        int semi = inner.indexOf(';');
        if (semi >= 0) inner = inner.substring(0, semi);
        // sip:user@host or sip:host
        int colon = inner.indexOf(':');
        if (colon >= 0) inner = inner.substring(colon + 1);
        int at = inner.indexOf('@');
        if (at >= 0) return inner.substring(0, at);
        return inner;
    }

    public String extractDisplayName(String header) {
        if (header == null) return null;
        int lt = header.indexOf('<');
        if (lt < 0) return null;
        String display = header.substring(0, lt).trim();
        // Remove quotes
        if (display.startsWith("\"") && display.endsWith("\"")) {
            display = display.substring(1, display.length() - 1);
        }
        return display.isEmpty() ? null : display;
    }

    public String extractUriFromHeader(String header) {
        if (header == null) return null;
        int lt = header.indexOf('<');
        int gt = header.indexOf('>');
        if (lt >= 0 && gt > lt) {
            return header.substring(lt + 1, gt).trim();
        }
        // No angle brackets - extract before semicolon
        int semi = header.indexOf(';');
        return semi >= 0 ? header.substring(0, semi).trim() : header.trim();
    }

    public String extractParam(String header, String param) {
        if (header == null) return null;
        String lower = header.toLowerCase();
        String search = param.toLowerCase() + "=";
        int idx = lower.indexOf(search);
        if (idx < 0) return null;
        String rest = header.substring(idx + search.length());
        // Handle quoted values
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end < 0 ? rest.substring(1) : rest.substring(1, end);
        }
        int semi = rest.indexOf(';');
        int comma = rest.indexOf(',');
        int end = -1;
        if (semi >= 0 && comma >= 0) end = Math.min(semi, comma);
        else if (semi >= 0) end = semi;
        else if (comma >= 0) end = comma;
        return end < 0 ? rest.trim() : rest.substring(0, end).trim();
    }

    @Override
    public String toString() {
        return raw;
    }
}
