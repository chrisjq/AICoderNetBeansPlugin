package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class BaseUrls {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private BaseUrls() {
    }

    public static String normalise(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        value = stripTrailingSlashes(value);
        if (endsWithPath(value, "/chat/completions")) {
            value = value.substring(0, value.length() - "/chat/completions".length());
        }
        value = stripTrailingSlashes(value);
        if (endsWithPath(value, "/v1")) {
            value = value.substring(0, value.length() - "/v1".length());
        }
        return stripTrailingSlashes(value);
    }

    public static boolean removedV1(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = stripTrailingSlashes(value);
        if (endsWithPath(value, "/chat/completions")) {
            value = stripTrailingSlashes(value.substring(0, value.length() - "/chat/completions".length()));
        }
        return endsWithPath(value, "/v1");
    }

    public static String root(String raw) {
        String value = normalise(raw);
        if (value.isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return value;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.indexOf(':') >= 0) {
                host = "[" + host + "]";
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        }
        catch (URISyntaxException ex) {
            return value;
        }
    }

    public static boolean isLocal(String url) {
        String host = hostOf(url);
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return isLocalIpv6(host);
        }
        if (IPV4.matcher(host).matches()) {
            return isLocalIpv4(host);
        }
        if (host.indexOf('.') < 0) {
            return true;
        }
        return host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")
                || host.endsWith(".internal");
    }

    public static boolean isHosted(String url, boolean keySet) {
        return !isLocal(url) && keySet;
    }

    public static List<String> apiBaseCandidates(String entered) {
        String value = entered == null ? "" : entered.trim();
        if (value.isEmpty()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        URI uri = parse(value);
        if (uri != null && uri.getScheme() != null && uri.getRawAuthority() != null) {
            candidates.add(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + "/v1");
        }
        if (!candidates.contains(value)) {
            candidates.add(value);
        }
        return List.copyOf(candidates);
    }

    private static String hostOf(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return null;
        }
        URI uri = parse(value);
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static URI parse(String value) {
        String text = value.contains("://") ? value : "http://" + value;
        try {
            return new URI(text);
        }
        catch (URISyntaxException ex) {
            return null;
        }
    }

    private static boolean isLocalIpv4(String host) {
        String[] parts = host.split("\\.");
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        return a == 127 || a == 10
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254);
    }

    private static boolean isLocalIpv6(String host) {
        byte[] address;
        try {
            address = InetAddress.getByName(host).getAddress();
        }
        catch (UnknownHostException ex) {
            return false;
        }
        if (address.length != 16) {
            return false;
        }
        int first = address[0] & 0xFF;
        int second = address[1] & 0xFF;
        return isLoopback(address) || (first & 0xFE) == 0xFC || (first == 0xFE && (second & 0xC0) == 0x80);
    }

    private static boolean isLoopback(byte[] address) {
        for (int i = 0; i < 15; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[15] == 1;
    }

    private static boolean endsWithPath(String value, String suffix) {
        return value.regionMatches(true, Math.max(0, value.length() - suffix.length()),
                                   suffix, 0, suffix.length());
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
