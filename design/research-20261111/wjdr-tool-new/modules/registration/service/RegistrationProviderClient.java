/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.http.MediaType
 *  org.springframework.http.ResponseEntity
 *  org.springframework.stereotype.Service
 *  org.springframework.util.LinkedMultiValueMap
 *  org.springframework.util.MultiValueMap
 *  org.springframework.web.client.RestClient$Builder
 *  org.springframework.web.client.RestClient$RequestBodySpec
 *  org.springframework.web.client.RestClientResponseException
 *  org.springframework.web.util.UriBuilder
 *  tools.jackson.core.JacksonException
 *  tools.jackson.core.type.TypeReference
 *  tools.jackson.databind.ObjectMapper
 */
package com.wjdr.tool.modules.registration.service;

import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import com.wjdr.tool.modules.registration.model.RegistrationProviderType;
import com.wjdr.tool.modules.registration.service.RegistrationProviderException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class RegistrationProviderClient {
    public static final String D1_BASE_URL = "https://api.d1jiema.com";
    private static final long NETWORK_ERROR = Long.MIN_VALUE;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> RESPONSE_TYPE = new TypeReference<Map<String, Object>>(){};
    private final RestClient.Builder restClientBuilder;

    public RegistrationProviderClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public ProviderSession authenticate(RegistrationProviderType providerType, String baseUrl, String account, String password) {
        if (providerType == RegistrationProviderType.D1) {
            if (account == null || account.isBlank()) {
                throw new RegistrationProviderException("D1 API Token \u4e0d\u80fd\u4e3a\u7a7a", 0L);
            }
            return new ProviderSession(account, Map.of("provider", providerType.label()));
        }
        return this.loginHaozhu(baseUrl, account, password);
    }

    private ProviderSession loginHaozhu(String baseUrl, String account, String password) {
        Map<String, Object> response = this.call(baseUrl, "login", Map.of("user", account, "pass", password));
        this.ensureSuccess(response, "\u63a5\u7801\u5e73\u53f0\u767b\u5f55\u5931\u8d25");
        String token = ProtocolValueSupport.firstText(response, "token");
        if (token.isBlank()) {
            throw new RegistrationProviderException("\u63a5\u7801\u5e73\u53f0\u767b\u5f55\u6210\u529f\u4f46\u672a\u8fd4\u56de token", 0L);
        }
        return new ProviderSession(token, this.withoutToken(response));
    }

    public ProviderBalance balance(RegistrationProviderType providerType, String baseUrl, String token) {
        if (providerType == RegistrationProviderType.D1) {
            String money = this.ensureD1Success(this.callD1(baseUrl, "leftAmount", Map.of("token", token)), "\u67e5\u8be2 D1 \u5e73\u53f0\u4f59\u989d\u5931\u8d25");
            return new ProviderBalance(money, 0L, Map.of("money", money));
        }
        Map<String, Object> response = this.call(baseUrl, "getSummary", Map.of("token", token));
        this.ensureSuccess(response, "\u67e5\u8be2\u63a5\u7801\u5e73\u53f0\u4f59\u989d\u5931\u8d25");
        return new ProviderBalance(ProtocolValueSupport.firstText(response, "money"), ProtocolValueSupport.number(response.get("num")), response);
    }

    public ProviderNumber acquireNumber(RegistrationProviderType providerType, String baseUrl, String token, String projectKey) {
        if (providerType == RegistrationProviderType.D1) {
            String phone = this.ensureD1Success(this.callD1(baseUrl, "getPhone", Map.of("token", token, "keyWord", projectKey)), "D1 \u5e73\u53f0\u53d6\u53f7\u5931\u8d25");
            return new ProviderNumber(phone, "", "", "", Map.of("provider", "D1"));
        }
        Map<String, Object> response = this.call(baseUrl, "getPhone", Map.of("token", token, "sid", projectKey));
        this.ensureSuccess(response, "\u63a5\u7801\u5e73\u53f0\u53d6\u53f7\u5931\u8d25");
        String phone = ProtocolValueSupport.firstText(response, "phone");
        if (phone.isBlank()) {
            throw new RegistrationProviderException("\u63a5\u7801\u5e73\u53f0\u53d6\u53f7\u6210\u529f\u4f46\u672a\u8fd4\u56de\u624b\u673a\u53f7", 0L);
        }
        return new ProviderNumber(phone, ProtocolValueSupport.firstText(response, "uid"), ProtocolValueSupport.firstText(response, "sp"), ProtocolValueSupport.firstText(response, "phone_gsd"), response);
    }

    public ProviderSmsResult pollSms(RegistrationProviderType providerType, String baseUrl, String token, String projectKey, String phone) {
        if (providerType == RegistrationProviderType.D1) {
            String sms = this.callD1(baseUrl, "getMsg", Map.of("token", token, "phone", phone, "keyWord", projectKey));
            if (sms.contains("[\u5c1a\u672a\u6536\u5230]")) {
                return new ProviderSmsResult(false, 1L, sms, sms, "", Map.of("waiting", true));
            }
            String content = this.ensureD1Success(sms, "D1 \u5e73\u53f0\u53d6\u7801\u5931\u8d25");
            String code = RegistrationProviderClient.extractVerificationCode(content).orElse("");
            return new ProviderSmsResult(true, 0L, "", content, code, Map.of("smsLength", content.length()));
        }
        Map<String, Object> response = this.call(baseUrl, "getMessage", Map.of("token", token, "sid", projectKey, "phone", phone));
        long code = ProtocolValueSupport.number(response.get("code"));
        String sms = ProtocolValueSupport.firstText(response, "sms");
        String verificationCode = ProtocolValueSupport.firstText(response, "yzm");
        return new ProviderSmsResult(code == 0L, code, ProtocolValueSupport.firstText(response, "msg"), sms, verificationCode, response);
    }

    public Map<String, Object> release(RegistrationProviderType providerType, String baseUrl, String token, String projectKey, String phone) {
        if (providerType.automaticNumberDisposal()) {
            return Map.of("provider", providerType.label(), "automatic", true);
        }
        Map<String, Object> response = this.call(baseUrl, "cancelRecv", Map.of("token", token, "sid", projectKey, "phone", phone));
        this.ensureSuccess(response, "\u91ca\u653e\u63a5\u7801\u53f7\u7801\u5931\u8d25");
        return response;
    }

    public Map<String, Object> blacklist(RegistrationProviderType providerType, String baseUrl, String token, String projectKey, String phone) {
        if (providerType.automaticNumberDisposal()) {
            return Map.of("provider", providerType.label(), "automatic", true);
        }
        Map<String, Object> response = this.call(baseUrl, "addBlacklist", Map.of("token", token, "sid", projectKey, "phone", phone));
        this.ensureSuccess(response, "\u62c9\u9ed1\u63a5\u7801\u53f7\u7801\u5931\u8d25");
        return response;
    }

    public static String normalizeBaseUrl(String rawBaseUrl) {
        URI uri;
        String raw;
        String string = raw = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("\u8bf7\u586b\u5199\u63a5\u7801\u5e73\u53f0\u670d\u52a1\u5668\u5730\u5740");
        }
        try {
            uri = URI.create(raw);
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("\u63a5\u7801\u5e73\u53f0\u670d\u52a1\u5668\u5730\u5740\u683c\u5f0f\u4e0d\u6b63\u786e", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
            throw new IllegalArgumentException("\u63a5\u7801\u5e73\u53f0\u670d\u52a1\u5668\u5730\u5740\u5fc5\u987b\u662f\u65e0\u8def\u5f84\u7684 HTTPS \u5730\u5740");
        }
        String host = uri.getHost().toLowerCase();
        if (host.equals("localhost") || host.endsWith(".local") || !host.contains(".")) {
            throw new IllegalArgumentException("\u63a5\u7801\u5e73\u53f0\u670d\u52a1\u5668\u5730\u5740\u4e0d\u80fd\u6307\u5411\u672c\u5730\u6216\u5185\u7f51\u4e3b\u673a");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isLinkLocalAddress() && !address.isSiteLocalAddress() && !address.isMulticastAddress()) continue;
                throw new IllegalArgumentException("\u63a5\u7801\u5e73\u53f0\u670d\u52a1\u5668\u5730\u5740\u4e0d\u80fd\u6307\u5411\u672c\u5730\u6216\u5185\u7f51\u4e3b\u673a");
            }
        }
        catch (UnknownHostException exception) {
            throw new IllegalArgumentException("\u63a5\u7801\u5e73\u53f0\u670d\u52a1\u5668\u5730\u5740\u65e0\u6cd5\u89e3\u6790", exception);
        }
        int port = uri.getPort();
        return "https://" + host + (String)(port > 0 ? ":" + port : "");
    }

    private Map<String, Object> call(String baseUrl, String api, Map<String, String> params) {
        String normalized = RegistrationProviderClient.normalizeBaseUrl(baseUrl);
        LinkedMultiValueMap form = new LinkedMultiValueMap();
        params.forEach((arg_0, arg_1) -> ((MultiValueMap)form).add(arg_0, arg_1));
        try {
            ResponseEntity response = ((RestClient.RequestBodySpec)this.restClientBuilder.baseUrl(normalized).build().post().uri(uriBuilder -> uriBuilder.path("/sms/").queryParam("api", new Object[]{api}).build(new Object[0]))).contentType(MediaType.APPLICATION_FORM_URLENCODED).body((Object)form).retrieve().toEntity(String.class);
            return this.parseResponse((ResponseEntity<String>)response);
        }
        catch (RestClientResponseException exception) {
            throw new RegistrationProviderException("\u63a5\u7801\u5e73\u53f0 HTTP " + exception.getStatusCode().value() + " \u8bf7\u6c42\u5931\u8d25", Long.MIN_VALUE, exception);
        }
        catch (RuntimeException exception) {
            if (exception instanceof RegistrationProviderException) {
                RegistrationProviderException providerException = (RegistrationProviderException)exception;
                throw providerException;
            }
            throw new RegistrationProviderException("\u65e0\u6cd5\u8fde\u63a5\u63a5\u7801\u5e73\u53f0: " + exception.getMessage(), Long.MIN_VALUE, exception);
        }
    }

    private String callD1(String baseUrl, String code, Map<String, String> params) {
        String normalized = RegistrationProviderClient.normalizeBaseUrl(baseUrl);
        try {
            ResponseEntity response = this.restClientBuilder.baseUrl(normalized).build().get().uri(uriBuilder -> {
                uriBuilder.path("/zc/data.php").queryParam("code", new Object[]{code});
                UriBuilder uriBuilder2 = uriBuilder;
                Objects.requireNonNull(uriBuilder2);
                UriBuilder rec$ = uriBuilder2;
                params.forEach((x$0, xva$1) -> rec$.queryParam(x$0, new Object[]{xva$1}));
                return uriBuilder.build(new Object[0]);
            }).retrieve().toEntity(String.class);
            return response.getBody() == null ? "" : ((String)response.getBody()).trim();
        }
        catch (RestClientResponseException exception) {
            throw new RegistrationProviderException("D1 \u5e73\u53f0 HTTP " + exception.getStatusCode().value() + " \u8bf7\u6c42\u5931\u8d25", Long.MIN_VALUE, exception);
        }
        catch (RuntimeException exception) {
            if (exception instanceof RegistrationProviderException) {
                RegistrationProviderException providerException = (RegistrationProviderException)exception;
                throw providerException;
            }
            throw new RegistrationProviderException("\u65e0\u6cd5\u8fde\u63a5 D1 \u63a5\u7801\u5e73\u53f0: " + exception.getMessage(), Long.MIN_VALUE, exception);
        }
    }

    private String ensureD1Success(String body, String fallbackMessage) {
        String value;
        String string = value = body == null ? "" : body.trim();
        if (value.isEmpty()) {
            throw new RegistrationProviderException(fallbackMessage + "\uff1a\u5e73\u53f0\u8fd4\u56de\u7a7a\u54cd\u5e94", 0L);
        }
        if (value.regionMatches(true, 0, "ERROR:", 0, "ERROR:".length())) {
            String message = value.substring("ERROR:".length()).trim();
            throw new RegistrationProviderException(message.isEmpty() ? fallbackMessage : message, 0L);
        }
        return value;
    }

    private Map<String, Object> parseResponse(ResponseEntity<String> response) {
        String body = (String)response.getBody();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Map parsed = (Map)OBJECT_MAPPER.readValue(body, RESPONSE_TYPE);
            return parsed == null ? Map.of() : ProtocolValueSupport.map(parsed);
        }
        catch (JacksonException exception) {
            MediaType contentType = response.getHeaders().getContentType();
            String type = contentType == null ? "\u672a\u77e5" : contentType.toString();
            throw new RegistrationProviderException("\u63a5\u7801\u5e73\u53f0\u8fd4\u56de\u4e86\u975e JSON \u54cd\u5e94\uff08HTTP " + response.getStatusCode().value() + "\uff0cContent-Type " + type + "\uff09\uff0c\u8bf7\u68c0\u67e5\u670d\u52a1\u5668\u5730\u5740\u3001\u4ee3\u7406/WAF \u8bbe\u7f6e\u6216\u5e73\u53f0\u72b6\u6001", Long.MIN_VALUE, exception);
        }
    }

    private void ensureSuccess(Map<String, Object> response, String fallbackMessage) {
        long code = ProtocolValueSupport.number(response.get("code"));
        if (code != 0L && code != 200L) {
            String message = ProtocolValueSupport.firstText(response, "msg", "message");
            throw new RegistrationProviderException(message.isBlank() ? fallbackMessage : message, code);
        }
    }

    private Map<String, Object> withoutToken(Map<String, Object> response) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<String, Object>(response);
        copy.remove("token");
        return copy;
    }

    public static Optional<String> extractVerificationCode(String sms) {
        if (sms == null || sms.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)").matcher(sms);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public record ProviderSession(String token, Map<String, Object> response) {
    }

    public record ProviderBalance(String money, long maxNumbers, Map<String, Object> response) {
    }

    public record ProviderNumber(String phone, String uid, String carrier, String region, Map<String, Object> response) {
    }

    public record ProviderSmsResult(boolean received, long code, String message, String sms, String verificationCode, Map<String, Object> response) {
    }
}

