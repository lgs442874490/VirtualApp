/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.registration.service;

import com.wjdr.tool.core.account.AccountCredentialCipher;
import com.wjdr.tool.core.account.OfficialLoginService;
import com.wjdr.tool.core.common.concurrency.VirtualThreadSupport;
import com.wjdr.tool.core.common.util.ConvertUtils;
import com.wjdr.tool.core.config.WjdrProperties;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ExpressRegistrationService {
    private static final String EXPRESS_METHOD = "express_signin";
    private static final String ID_CARD_METHOD = "idCardVerified";
    private static final String ID_CARD_CHECK_WAY = "2";
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^\\d{17}[\\dXx]$");
    private static final int MAX_BATCH_SIZE = 200;
    private static final int BATCH_CONCURRENCY = 8;
    private final WjdrProperties properties;
    private final OfficialLoginService officialLoginService;

    public ExpressRegistrationService(WjdrProperties properties, OfficialLoginService officialLoginService) {
        this.properties = properties;
        this.officialLoginService = officialLoginService;
    }

    public Map<String, Object> createExpressAccount() {
        WjdrProperties.OfficialLogin config = this.properties.getOfficialLogin();
        String androidId = ProtocolValueSupport.firstNonBlankText(config.getProfileDeviceId(), config.getProfileDistinctId());
        String guid = this.md5Hex(androidId + String.valueOf(UUID.randomUUID()));
        LinkedHashMap<String, Object> expressPayload = new LinkedHashMap<String, Object>();
        expressPayload.put("require_bound_info", "true");
        expressPayload.put("guid", guid);
        expressPayload.put("origin_guid", this.md5Hex(androidId + config.getOriginAddress()));
        if (!androidId.isEmpty()) {
            expressPayload.put("android_id", androidId);
        }
        expressPayload.put("origin_address", config.getOriginAddress());
        expressPayload.put("install_channelid", config.getProfileAdSource());
        Map<String, Object> expressResponse = this.officialLoginService.callPassport(config.getExpressSignUpPath(), EXPRESS_METHOD, expressPayload);
        Map<String, Object> expressData = this.requireSuccess(expressResponse, "\u6e38\u5ba2\u5feb\u901f\u6ce8\u518c\u5931\u8d25");
        String fpid = this.fieldText(expressData, "fpid", "account");
        String sessionKey = this.fieldText(expressData, "session_key", "sessionKey", "sdkToken", "sdk_token");
        if (fpid.isEmpty() || sessionKey.isEmpty()) {
            throw new IllegalStateException("\u6e38\u5ba2\u5feb\u901f\u6ce8\u518c\u672a\u8fd4\u56de\u767b\u5f55\u6001");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("fpid", fpid);
        result.put("sessionKey", sessionKey);
        result.put("encrypted", AccountCredentialCipher.encode(fpid, sessionKey));
        return result;
    }

    public Map<String, Object> createExpressAccounts(int requestedCount) {
        if (requestedCount < 1 || requestedCount > 200) {
            throw new IllegalArgumentException("\u6279\u91cf\u6ce8\u518c\u6570\u91cf\u5fc5\u987b\u4e3a 1-200");
        }
        ArrayList<Map> accounts = new ArrayList<Map>(requestedCount);
        ArrayList failures = new ArrayList();
        try (ExecutorService executor = VirtualThreadSupport.perTaskExecutor("express-registration-");){
            int index;
            Semaphore permits = new Semaphore(8);
            ArrayList<Future<Map>> futures = new ArrayList<Future<Map>>(requestedCount);
            for (index = 0; index < requestedCount; ++index) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        Map<String, Object> map = this.createExpressAccount();
                        return map;
                    }
                    finally {
                        permits.release();
                    }
                }));
            }
            for (index = 0; index < futures.size(); ++index) {
                try {
                    accounts.add((Map)((Future)futures.get(index)).get());
                    continue;
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("\u6279\u91cf\u6ce8\u518c\u88ab\u4e2d\u65ad", ex);
                }
                catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    LinkedHashMap<String, Object> failure = new LinkedHashMap<String, Object>();
                    failure.put("index", index + 1);
                    failure.put("message", cause == null || cause.getMessage() == null ? "\u6e38\u5ba2\u8d26\u53f7\u751f\u6210\u5931\u8d25" : cause.getMessage());
                    failures.add(failure);
                }
            }
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("requestedCount", requestedCount);
        result.put("successCount", accounts.size());
        result.put("failureCount", failures.size());
        result.put("accounts", accounts);
        result.put("failures", failures);
        return result;
    }

    public Map<String, Object> verifyRealName(Map<String, Object> payload) {
        Map<String, Object> source = payload == null ? Map.of() : payload;
        String account = this.fieldText(source, "account", "fpid");
        String sessionKey = this.fieldText(source, "sdkToken", "session_key", "sessionKey", "sdk_token");
        String name = this.fieldText(source, "name", "real_name", "realName");
        String idCard = this.fieldText(source, "id_card", "idCard", "idcard");
        this.requireText(account, "\u7f3a\u5c11\u8d26\u53f7 account");
        this.requireText(sessionKey, "\u7f3a\u5c11\u767b\u5f55\u4ee4\u724c sdkToken");
        this.requireText(name, "\u7f3a\u5c11\u5b9e\u540d\u59d3\u540d name");
        this.requireText(idCard, "\u7f3a\u5c11\u8eab\u4efd\u8bc1\u53f7 id_card");
        if (!ID_CARD_PATTERN.matcher(idCard).matches()) {
            throw new IllegalArgumentException("\u8eab\u4efd\u8bc1\u53f7\u683c\u5f0f\u4e0d\u6b63\u786e");
        }
        WjdrProperties.OfficialLogin config = this.properties.getOfficialLogin();
        LinkedHashMap<String, Object> verifyPayload = new LinkedHashMap<String, Object>();
        verifyPayload.put("check_way", ID_CARD_CHECK_WAY);
        verifyPayload.put("name", name);
        verifyPayload.put("id_card", idCard);
        verifyPayload.put("fpid", account);
        verifyPayload.put("session_key", sessionKey);
        Map<String, Object> verifyResponse = this.officialLoginService.callPassport(config.getIdCardVerifiedPath(), ID_CARD_METHOD, verifyPayload);
        Map<String, Object> verifyData = this.requireSuccess(verifyResponse, "\u5b9e\u540d\u8ba4\u8bc1\u5931\u8d25");
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("account", account);
        result.put("realNameVerified", true);
        String verifyMessage = this.fieldText(verifyData, "msg");
        if (!verifyMessage.isEmpty()) {
            result.put("realNameMessage", verifyMessage);
        }
        return result;
    }

    private Map<String, Object> requireSuccess(Map<String, Object> response, String failureMessage) {
        Map<String, Object> data = this.map(response.get("data"));
        if (this.isSuccessful(response, data)) {
            return data;
        }
        String message = this.fieldText(data, "msg");
        throw new IllegalStateException(message.isEmpty() ? failureMessage : message);
    }

    private boolean isSuccessful(Map<String, Object> response, Map<String, Object> data) {
        if (ConvertUtils.toLong(response.get("status"), 0L) != 1L) {
            return false;
        }
        return ConvertUtils.toLong(data.get("code"), -1L) != 0L;
    }

    private String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value & 0xFF));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("\u751f\u6210\u8bbe\u5907\u6807\u8bc6\u5931\u8d25", ex);
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private String text(Object value) {
        return ConvertUtils.toStringValue(value).trim();
    }

    private String fieldText(Map<String, Object> source, String ... keys) {
        for (String key : keys) {
            String text = this.text(source.get(key));
            if (text.isEmpty()) continue;
            return text;
        }
        return "";
    }

    private Map<String, Object> map(Object raw) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        if (raw instanceof Map) {
            Map rawMap = (Map)raw;
            rawMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }
}

