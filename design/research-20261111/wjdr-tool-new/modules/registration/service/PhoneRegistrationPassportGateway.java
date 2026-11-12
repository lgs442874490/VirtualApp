/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  java.lang.MatchException
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.registration.service;

import com.wjdr.tool.core.account.OfficialLoginService;
import com.wjdr.tool.core.config.WjdrProperties;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PhoneRegistrationPassportGateway {
    private static final String CAPTCHA_VALIDATION_METHOD = "captchaValidation";
    private static final String REGISTER_WITH_SMS_METHOD = "registerWithSms";
    private static final String ID_CARD_VERIFIED_METHOD = "idCardVerified";
    private static final String CAPTCHA_TYPE_FIELD = "type";
    private static final String REGISTER_CAPTCHA_TYPE = "1";
    private static final String ID_CARD_CHECK_WAY = "2";
    private final WjdrProperties properties;
    private final OfficialLoginService officialLoginService;

    public PhoneRegistrationPassportGateway(WjdrProperties properties, OfficialLoginService officialLoginService) {
        this.properties = properties;
        this.officialLoginService = officialLoginService;
    }

    public PassportSendResult sendCaptcha(String mobile) {
        boolean success;
        Map<String, Object> result = this.officialLoginService.sendSms(Map.of("mobile", mobile, "length", "4"));
        Map<String, Object> response = ProtocolValueSupport.map(result.get("passport_response"));
        Map<String, Object> data = ProtocolValueSupport.map(response.get("data"));
        long status = ProtocolValueSupport.number(response.get("status"));
        long code = ProtocolValueSupport.number(data.get("code"));
        String message = ProtocolValueSupport.firstText(data, "msg", "message");
        boolean bl = success = !(status != 0L && status != 1L || !data.isEmpty() && code < 0L);
        return new PassportSendResult(success, status, code, message.isBlank() ? (success ? "\u77ed\u4fe1\u5df2\u53d1\u9001" : "\u77ed\u4fe1\u53d1\u9001\u5931\u8d25") : message);
    }

    public PassportCaptchaResult validateCaptcha(String mobile, String code) {
        Map<String, Object> payload = PhoneRegistrationPassportGateway.payload("mobile", mobile, "code", code, CAPTCHA_TYPE_FIELD, REGISTER_CAPTCHA_TYPE);
        PassportCallResult response = this.callPassport(this.properties.getOfficialLogin().getSmsLoginPath(), CAPTCHA_VALIDATION_METHOD, payload);
        String token = ProtocolValueSupport.firstText(response.data(), "token");
        boolean success = response.status() == 1L && !token.isBlank();
        return new PassportCaptchaResult(success, response.status(), response.code(), response.message().isBlank() ? (success ? "\u9a8c\u8bc1\u7801\u6821\u9a8c\u901a\u8fc7" : "\u9a8c\u8bc1\u7801\u6821\u9a8c\u5931\u8d25") : response.message(), token);
    }

    public PassportRegistrationResult registerWithSms(String mobile, String registrationToken, String password) {
        String installChannel;
        WjdrProperties.OfficialLogin config = this.properties.getOfficialLogin();
        Map<String, Object> payload = PhoneRegistrationPassportGateway.payload("token", registrationToken, "mobile", mobile, "password", password);
        String androidId = ProtocolValueSupport.firstNonBlankText(config.getProfileDeviceId(), config.getProfileDistinctId());
        if (!androidId.isBlank()) {
            payload.put("android_id", androidId);
        }
        if ((installChannel = config.getProfileAdSource()) != null && !installChannel.isBlank()) {
            payload.put("install_channelid", installChannel);
        }
        PassportCallResult response = this.callPassport(config.getSmsLoginPath(), REGISTER_WITH_SMS_METHOD, payload);
        String account = ProtocolValueSupport.firstText(response.data(), "fpid", "account");
        String sdkToken = ProtocolValueSupport.firstText(response.data(), "session_key", "sessionKey", "sdkToken");
        boolean success = response.status() == 1L && !account.isBlank() && !sdkToken.isBlank();
        return new PassportRegistrationResult(success, response.status(), response.code(), response.message().isBlank() ? (success ? "\u624b\u673a\u53f7\u8d26\u53f7\u6ce8\u518c\u6210\u529f" : "\u624b\u673a\u53f7\u8d26\u53f7\u6ce8\u518c\u5931\u8d25") : response.message(), account, sdkToken);
    }

    public PassportRealNameResult verifyRealName(String account, String sdkToken, String name, String idCard) {
        Map<String, Object> payload = PhoneRegistrationPassportGateway.payload("check_way", ID_CARD_CHECK_WAY, "name", name, "id_card", idCard, "fpid", account, "session_key", sdkToken);
        PassportCallResult response = this.callPassport(this.properties.getOfficialLogin().getIdCardVerifiedPath(), ID_CARD_VERIFIED_METHOD, payload);
        RealNameOutcome outcome = this.realNameOutcome(response.status(), response.code(), response.message());
        return new PassportRealNameResult(outcome, response.status(), response.code(), response.message().isBlank() ? this.defaultRealNameMessage(outcome) : response.message());
    }

    private PassportCallResult callPassport(String path, String method, Map<String, Object> payload) {
        Map<String, Object> response = this.officialLoginService.callPassport(path, method, payload);
        Map<String, Object> data = ProtocolValueSupport.map(response.get("data"));
        return new PassportCallResult(data, ProtocolValueSupport.number(response.get("status")), ProtocolValueSupport.number(data.get("code")), ProtocolValueSupport.firstText(data, "msg", "message"));
    }

    private static Map<String, Object> payload(Object ... entries) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }

    private RealNameOutcome realNameOutcome(long status, long code, String message) {
        if (status == 1L && code == 1L) {
            return RealNameOutcome.VERIFIED;
        }
        if (status == 1L && code == 2L || code == 2406L || this.containsAlreadyVerified(message)) {
            return RealNameOutcome.ALREADY_VERIFIED;
        }
        return RealNameOutcome.FAILED;
    }

    private boolean containsAlreadyVerified(String message) {
        String normalized = message == null ? "" : message.trim().toLowerCase();
        return normalized.contains("\u65e0\u9700\u5b9e\u540d") || normalized.contains("\u5df2\u5b9e\u540d") || normalized.contains("\u5df2\u7ecf\u5b9e\u540d") || normalized.contains("\u5df2\u8ba4\u8bc1") || normalized.contains("already verified");
    }

    private String defaultRealNameMessage(RealNameOutcome outcome) {
        return switch (outcome.ordinal()) {
            default -> throw new MatchException(null, null);
            case 0 -> "\u5b9e\u540d\u8ba4\u8bc1\u6210\u529f";
            case 1 -> "\u8be5\u8d26\u53f7\u5df2\u7ecf\u5b9e\u540d\uff0c\u53f7\u7801\u53ef\u80fd\u5df2\u88ab\u4f7f\u7528";
            case 2 -> "\u5b9e\u540d\u8ba4\u8bc1\u5931\u8d25";
        };
    }

    public record PassportSendResult(boolean success, long status, long code, String message) {
    }

    private record PassportCallResult(Map<String, Object> data, long status, long code, String message) {
    }

    public record PassportCaptchaResult(boolean success, long status, long code, String message, String token) {
    }

    public record PassportRegistrationResult(boolean success, long status, long code, String message, String account, String sdkToken) {
    }

    public static enum RealNameOutcome {
        VERIFIED,
        ALREADY_VERIFIED,
        FAILED;

    }

    public record PassportRealNameResult(RealNameOutcome outcome, long status, long code, String message) {
    }
}

