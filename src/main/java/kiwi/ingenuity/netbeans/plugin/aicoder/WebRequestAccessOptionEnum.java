package kiwi.ingenuity.netbeans.plugin.aicoder;

import java.util.Locale;

public enum WebRequestAccessOptionEnum {
    GET("GET", AccessControlLabelEnum.ALLOW_WEB_REQUEST_GET),
    POST("POST", AccessControlLabelEnum.ALLOW_WEB_REQUEST_POST),
    PUT("PUT", AccessControlLabelEnum.ALLOW_WEB_REQUEST_PUT),
    PATCH("PATCH", AccessControlLabelEnum.ALLOW_WEB_REQUEST_PATCH),
    DELETE("DELETE", AccessControlLabelEnum.ALLOW_WEB_REQUEST_DELETE),
    HEAD("HEAD", AccessControlLabelEnum.ALLOW_WEB_REQUEST_HEAD),
    OPTIONS("OPTIONS", AccessControlLabelEnum.ALLOW_WEB_REQUEST_OPTIONS),
    HEADERS(null, AccessControlLabelEnum.ALLOW_WEB_REQUEST_HEADERS),
    BODY(null, AccessControlLabelEnum.ALLOW_WEB_REQUEST_BODY);

    private final String methodName;
    private final AccessControlLabelEnum label;

    WebRequestAccessOptionEnum(String methodName, AccessControlLabelEnum label) {
        this.methodName = methodName;
        this.label = label;
    }

    public boolean isMethod() {
        return methodName != null;
    }

    public String methodName() {
        return methodName;
    }

    public AccessControlLabelEnum label() {
        return label;
    }

    public static WebRequestAccessOptionEnum forMethod(String method) {
        String normalised = method.trim().toUpperCase(Locale.ROOT);
        for (WebRequestAccessOptionEnum option : values()) {
            if (option.isMethod() && normalised.equals(option.methodName())) {
                return option;
            }
        }
        throw new IllegalArgumentException("Unsupported method: " + method);
    }
}
