/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.registration.service;

import com.wjdr.tool.core.account.ConfigJsonLoader;
import com.wjdr.tool.core.common.error.GameErrorCatalog;
import com.wjdr.tool.core.common.error.GameErrorDescriptor;
import com.wjdr.tool.core.common.util.ConvertUtils;
import com.wjdr.tool.core.config.WjdrProperties;
import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.GameProtocolClient;
import com.wjdr.tool.core.protocol.PortableSprotoCodec;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import com.wjdr.tool.core.protocol.SprotoSchemaParser;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RegistrationRoleCreationService {
    private static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(30L);
    private final ConfigJsonLoader configJsonLoader;
    private final GameErrorCatalog gameErrorCatalog;
    private final Supplier<GameProtocolClient> clientFactory;

    @Autowired
    public RegistrationRoleCreationService(WjdrProperties properties, ConfigJsonLoader configJsonLoader, GameErrorCatalog gameErrorCatalog) {
        this(configJsonLoader, gameErrorCatalog, () -> new GameProtocolClient(properties, new PortableSprotoCodec(new SprotoSchemaParser())));
    }

    RegistrationRoleCreationService(ConfigJsonLoader configJsonLoader, GameErrorCatalog gameErrorCatalog, Supplier<GameProtocolClient> clientFactory) {
        this.configJsonLoader = configJsonLoader;
        this.gameErrorCatalog = gameErrorCatalog;
        this.clientFactory = clientFactory;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public Map<String, Object> createRole(Map<String, Object> payload) {
        Map<Object, Object> source = payload == null ? Map.of() : payload;
        String account = ProtocolValueSupport.firstNonBlankText(source.get("account"), source.get("fpid"));
        String sdkToken = ProtocolValueSupport.firstNonBlankText(source.get("sdkToken"), ProtocolValueSupport.firstNonBlankText(source.get("session_key"), source.get("sessionKey")));
        long serverId = ProtocolValueSupport.number(ProtocolValueSupport.firstNonBlankText(source.get("serverId"), ProtocolValueSupport.firstNonBlankText(source.get("server_id"), source.get("serverid"))));
        if (account.isEmpty() || sdkToken.isEmpty()) {
            throw new IllegalArgumentException("\u7f3a\u5c11\u8d26\u53f7\u6216\u767b\u5f55\u4ee4\u724c\uff0c\u8bf7\u5148\u5b8c\u6210\u8d26\u53f7\u6ce8\u518c");
        }
        if (serverId <= 0L) {
            throw new IllegalArgumentException("\u8bf7\u586b\u5199\u76ee\u6807\u533a\u670d\u7f16\u53f7");
        }
        LinkedHashMap<String, Object> loginAccount = new LinkedHashMap<String, Object>();
        loginAccount.put("account", account);
        loginAccount.put("sdkToken", sdkToken);
        loginAccount.put("server_id", serverId);
        loginAccount.put("is_create", true);
        GameProtocolClient client = this.clientFactory.get();
        try {
            client.initialize();
            GameMessage response = client.request("loginauth", this.configJsonLoader.buildLoginConfig(loginAccount), LOGIN_TIMEOUT);
            Map<String, Object> result = this.interpret(account, serverId, response);
            this.appendClientDefaultRole(result, account, sdkToken);
            Map<String, Object> map = result;
            return map;
        }
        finally {
            client.shutdown();
        }
    }

    private void appendClientDefaultRole(Map<String, Object> result, String account, String sdkToken) {
        long createdUid = ProtocolValueSupport.number(result.get("uid"));
        Map<String, Object> current = this.readCurrentRole(account, sdkToken);
        long currentUid = ProtocolValueSupport.number(current.get("uid"));
        if (currentUid <= 0L) {
            return;
        }
        long currentServerId = ProtocolValueSupport.number(current.get("serverid"));
        boolean sameRole = currentUid == createdUid;
        result.put("clientDefaultSameRole", sameRole);
        if (currentServerId > 0L) {
            result.put("clientDefaultServerId", currentServerId);
        }
        String message = String.valueOf(result.get("message"));
        result.put("message", sameRole ? message + "\uff1b\u8be5\u8d26\u53f7\u5728\u5ba2\u6237\u7aef\u767b\u5f55\u4f1a\u76f4\u63a5\u8fdb\u5165\u8fd9\u4e2a\u89d2\u8272" : message + "\uff1b\u6ce8\u610f\uff1a\u5ba2\u6237\u7aef\u767b\u5f55\u9ed8\u8ba4\u8fdb\u5165\u7684\u662f\u533a\u670d " + currentServerId + " \u7684\u89d2\u8272\uff08uid=" + currentUid + "\uff09");
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private Map<String, Object> readCurrentRole(String account, String sdkToken) {
        LinkedHashMap<String, Object> loginAccount = new LinkedHashMap<String, Object>();
        loginAccount.put("account", account);
        loginAccount.put("sdkToken", sdkToken);
        GameProtocolClient client = this.clientFactory.get();
        try {
            Map content;
            client.initialize();
            GameMessage response = client.request("loginauth", this.configJsonLoader.buildLoginConfig(loginAccount), LOGIN_TIMEOUT);
            Map<Object, Object> map = content = response == null || response.getContent() == null ? Map.of() : response.getContent();
            if (ProtocolValueSupport.number(content.get("code")) != 0L) {
                Map<String, Object> map2 = Map.of();
                return map2;
            }
            LinkedHashMap<String, Object> current = new LinkedHashMap<String, Object>();
            current.put("uid", ProtocolValueSupport.number(content.get("uid")));
            current.put("serverid", ProtocolValueSupport.number(content.get("serverid")));
            LinkedHashMap<String, Object> linkedHashMap = current;
            return linkedHashMap;
        }
        catch (RuntimeException ignored) {
            Map<String, Object> map = Map.of();
            return map;
        }
        finally {
            client.shutdown();
        }
    }

    private Map<String, Object> interpret(String account, long requestedServerId, GameMessage response) {
        boolean createdNew;
        Map<Object, Object> content = response == null || response.getContent() == null ? Map.of() : response.getContent();
        int code = (int)ProtocolValueSupport.number(content.get("code"));
        if (code != 0) {
            throw new IllegalStateException("\u5efa\u53f7\u767b\u5f55\u88ab\u6e38\u620f\u670d\u62d2\u7edd\uff1a" + this.describeCode(code));
        }
        long uid = ProtocolValueSupport.number(content.get("uid"));
        long actualServerId = ProtocolValueSupport.number(content.get("serverid"));
        long blockEndtime = ProtocolValueSupport.number(content.get("block_create_role_endtime"));
        if (uid <= 0L) {
            throw new IllegalStateException("\u5efa\u53f7\u5931\u8d25\uff1a\u670d\u52a1\u7aef\u6ca1\u6709\u521b\u5efa\u89d2\u8272" + RegistrationRoleCreationService.blockHint(blockEndtime));
        }
        Object rawIsNew = content.get("is_new");
        Object rawIsFirstRole = content.get("is_first_role");
        boolean flagsPresent = rawIsNew != null || rawIsFirstRole != null;
        boolean bl = createdNew = RegistrationRoleCreationService.flagEnabled(rawIsNew) || RegistrationRoleCreationService.flagEnabled(rawIsFirstRole);
        if (flagsPresent && !createdNew) {
            throw new IllegalStateException("\u5efa\u53f7\u5931\u8d25\uff1a\u670d\u52a1\u7aef\u8fd4\u56de\u7684\u662f\u8d26\u53f7\u5df2\u6709\u89d2\u8272\uff08uid=" + uid + "\uff09" + RegistrationRoleCreationService.blockHint(blockEndtime));
        }
        long serverId = actualServerId > 0L ? actualServerId : requestedServerId;
        boolean mismatch = serverId != requestedServerId;
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("account", account);
        result.put("requestedServerId", requestedServerId);
        result.put("serverId", serverId);
        result.put("serverMismatch", mismatch);
        result.put("uid", uid);
        result.put("created", true);
        result.put("message", mismatch ? "\u670d\u52a1\u7aef\u6ca1\u6709\u628a\u89d2\u8272\u5efa\u5728\u533a\u670d " + requestedServerId + "\uff0c\u800c\u662f\u5efa\u5230\u4e86\u533a\u670d " + serverId + "\uff08uid=" + uid + "\uff09\u3002\u8be5\u89d2\u8272\u5df2\u7ecf\u771f\u5b9e\u5b58\u5728\uff0c\u53ef\u5728\u6e38\u620f\u5185\u5220\u9664\uff0c\u6216\u6362\u4e00\u4e2a\u533a\u670d\u91cd\u8bd5" : "\u5df2\u5728\u533a\u670d " + requestedServerId + " \u521b\u5efa\u89d2\u8272\uff08uid=" + uid + "\uff09");
        return result;
    }

    private String describeCode(int code) {
        GameErrorDescriptor descriptor = this.gameErrorCatalog.resolve(code);
        if (descriptor == null) {
            return "\u9519\u8bef\u7801 " + code;
        }
        String chinese = RegistrationRoleCreationService.text(descriptor.chineseDescription());
        return chinese.isEmpty() ? "\u9519\u8bef\u7801 " + code + "\uff08" + descriptor.key() + "\uff09" : "\u9519\u8bef\u7801 " + code + "\uff08" + chinese + "\uff09";
    }

    private static String blockHint(long blockEndtime) {
        return blockEndtime <= 0L ? "" : "\uff08\u670d\u52a1\u7aef\u7981\u6b62\u8be5\u8d26\u53f7\u5efa\u53f7\u81f3\u65f6\u95f4\u6233 " + blockEndtime + "\uff09";
    }

    private static boolean flagEnabled(Object raw) {
        if (raw instanceof Boolean) {
            Boolean value = (Boolean)raw;
            return value;
        }
        if (raw instanceof Number) {
            Number number = (Number)raw;
            return number.doubleValue() != 0.0;
        }
        String value = RegistrationRoleCreationService.text(raw);
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static String text(Object value) {
        return ConvertUtils.toStringValue(value).trim();
    }
}

