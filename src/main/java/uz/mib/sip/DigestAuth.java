package uz.mib.sip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public class DigestAuth {

    public static String buildAuthorization(String user, String pass, String method,
                                             String uri, String wwwAuthHeader) {
        if (wwwAuthHeader == null || wwwAuthHeader.isEmpty()) return null;

        // Strip "Digest " prefix
        String authInfo = wwwAuthHeader;
        if (authInfo.toLowerCase().startsWith("digest ")) {
            authInfo = authInfo.substring(7);
        }

        String realm = extractParam(authInfo, "realm");
        String nonce = extractParam(authInfo, "nonce");
        String qop = extractParam(authInfo, "qop");
        String opaque = extractParam(authInfo, "opaque");
        String algorithm = extractParam(authInfo, "algorithm");

        if (realm == null || nonce == null) return null;

        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5(method + ":" + uri);

        String response;
        String nc = "00000001";
        String cnonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // Check if qop contains "auth"
        boolean useQop = qop != null && qop.toLowerCase().contains("auth");

        if (useQop) {
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(user).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uri).append("\", ");

        if (algorithm != null && !algorithm.isEmpty()) {
            sb.append("algorithm=").append(algorithm).append(", ");
        }

        if (useQop) {
            sb.append("qop=auth, ");
            sb.append("nc=").append(nc).append(", ");
            sb.append("cnonce=\"").append(cnonce).append("\", ");
        }

        sb.append("response=\"").append(response).append("\"");

        if (opaque != null && !opaque.isEmpty()) {
            sb.append(", opaque=\"").append(opaque).append("\"");
        }

        return sb.toString();
    }

    private static String extractParam(String authInfo, String param) {
        String lower = authInfo.toLowerCase();
        String search = param.toLowerCase() + "=";
        int idx = lower.indexOf(search);
        if (idx < 0) return null;
        String rest = authInfo.substring(idx + search.length()).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end < 0 ? rest.substring(1) : rest.substring(1, end);
        }
        int end = rest.indexOf(',');
        int semi = rest.indexOf(';');
        if (semi >= 0 && (end < 0 || semi < end)) end = semi;
        return end < 0 ? rest.trim() : rest.substring(0, end).trim();
    }

    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
