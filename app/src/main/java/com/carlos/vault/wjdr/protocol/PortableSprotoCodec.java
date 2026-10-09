package com.carlos.vault.wjdr.protocol;

import com.carlos.vault.wjdr.WjdrLog;

import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vault.wjdr.protocol.LocalSprotoSchemaOverrides;
import com.carlos.vault.wjdr.protocol.Rc4StreamCodec;
import com.carlos.vault.wjdr.protocol.SprotoBinarySupport;
import com.carlos.vault.wjdr.protocol.SprotoCodec;
import com.carlos.vault.wjdr.protocol.SprotoFieldDef;
import com.carlos.vault.wjdr.protocol.SprotoProtocolDef;
import com.carlos.vault.wjdr.protocol.SprotoSchema;
import com.carlos.vault.wjdr.protocol.SprotoSchemaParser;
import com.carlos.vault.wjdr.protocol.SprotoTypeDef;
import com.carlos.vault.wjdr.protocol.XorCodec;
import com.carlos.vault.wjdr.protocol.PortableSprotoCodecDecodedValue;
import com.carlos.vault.wjdr.protocol.PortableSprotoCodecEncodedField;
import com.carlos.vault.wjdr.protocol.PortableSprotoCodecPackedSegment;
import com.carlos.vault.wjdr.protocol.PortableSprotoCodecSchemaBundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class PortableSprotoCodec
implements SprotoCodec {
        private static final String TAG = "WjdrProto";
    private final Map<Integer, Boolean> warnedUnknownProtocols = new ConcurrentHashMap<Integer, Boolean>();
    private final Map<Integer, Boolean> warnedSessionCollisions = new ConcurrentHashMap<Integer, Boolean>();
    private final Map<Integer, Boolean> warnedUnmappedResponses = new ConcurrentHashMap<Integer, Boolean>();
    private static final int PACK_SEGMENT_SIZE = 8;
    private static final int PACK_FF_MARKER = 255;
    private static final int FF_PLACEHOLDER_BYTES = 10;
    private static final long INLINE_INTEGER_LIMIT = 32767L;
    private static final String PACKAGE_TYPE_NAME = "package";
    static final int SIZEOF_LENGTH = 4;
    static final int SIZEOF_HEADER = 2;
    static final int SIZEOF_FIELD = 2;
    static final int SIZEOF_INT32 = 4;
    static final int SIZEOF_INT64 = 8;
    static final int SPROTO_TINTEGER = 0;
    static final int SPROTO_TBOOLEAN = 1;
    static final int SPROTO_TSTRING = 2;
    static final int SPROTO_TDOUBLE = 3;
    static final int SPROTO_TSTRUCT = 4;
    static final int SPROTO_TARRAY = 128;
    static final int SPROTO_TSTRING_STRING = 0;
    static final int SPROTO_TSTRING_BINARY = 1;
    private static final SprotoTypeDef NO_RESPONSE = new SprotoTypeDef();
    private static final int NO_CRYPT_METHOD = 0;
    private static final int XOR_CRYPT_METHOD = 1;
    private static final int RC4_CRYPT_METHOD = 2;
    private static final Set<String> XOR_PROTOCOLS = Set.of("req_chat_send_to_self", "req_chat_send_p2p", "req_chat_cross_capital_send_kingdom", "req_chat_send_kingdom", "req_chat_send_alliance", "req_chat_send_room", "req_chat_send_to_uids", "req_upgrade_building", "req_upgrade_furniture", "req_create_furniture", "req_begin_research", "req_research_speed_up", "req_research_complete_immediately", "req_barrack_train_soldier", "req_barrack_upgrade_soldier", "req_barrack_speed_up", "req_get_hang_up_reward", "req_receive_online_reward", "req_world_march", "req_world_map_search", "req_world_map_info", "req_get_chapterquest_award", "req_get_chapter_award", "req_get_dailyquest_award", "req_get_growthquest_award", "req_arena_fight", "req_intelligence_receive", "req_player_modify_name", "req_player_modify_image", "req_players_base_info", "req_players_base_info_with_permission", "req_player_detail_info", "req_player_other_record", "req_hospital_heal_soldier", "req_hospital_speed_up", "req_building_queue_speed", "req_queue_ask_for_help", "req_alliance_help_all", "req_save_client_data", "req_hero_upgrade", "req_hero_promote_star", "req_recruit_hero", "req_hero_search_times", "req_survivor_send_or_fired", "req_survivor_work_start", "req_vip_get_daily_free_gift", "req_homeland_like_homeland", "req_homeland_take_life_tree_coin");
    private final SprotoSchemaParser schemaParser;
    private final Map<Integer, SprotoTypeDef> responseSessions = new ConcurrentHashMap<Integer, SprotoTypeDef>();
    private final Map<Integer, String> protocolSessions = new ConcurrentHashMap<Integer, String>();
    private final Map<Integer, Long> sessionRegisteredAt = new ConcurrentHashMap<Integer, Long>();
    private static final int SESSION_REGISTRY_SWEEP_THRESHOLD = 2048;
    private static final long SESSION_REGISTRY_TTL_MILLIS = 600000L;
    private volatile SprotoSchema c2sSchema;
    private volatile SprotoTypeDef c2sPackageType;
    private volatile SprotoSchema s2cSchema;
    private volatile SprotoTypeDef s2cPackageType;
    private final AtomicReference<byte[]> secretKey = new AtomicReference<byte[]>("4b5eea17".getBytes(StandardCharsets.UTF_8));
    private volatile Rc4StreamCodec outboundRc4 = new Rc4StreamCodec(this.secretKey.get());
    private volatile Rc4StreamCodec inboundRc4 = new Rc4StreamCodec(this.secretKey.get());
    private final ReentrantLock keyUpdateLock = new ReentrantLock();
    private volatile int defaultCryptMethod;

    public PortableSprotoCodec(SprotoSchemaParser schemaParser) {
        this.schemaParser = schemaParser;
    }

    public void loadSchemas(byte[] c2sSchema, byte[] s2cSchema) {
        try {
            // 安卓版：schema 由调用方从 assets 读出 byte[] 直接注入（单进程单次加载，去掉磁盘缓存）
            this.c2sSchema = sharedC2s(c2sSchema);
            this.s2cSchema = sharedS2c(s2cSchema);
            this.c2sPackageType = this.c2sSchema.requireType(PACKAGE_TYPE_NAME);
            this.s2cPackageType = this.s2cSchema.requireType(PACKAGE_TYPE_NAME);
        }
        catch (Exception ex) {
            throw new IllegalStateException("加载 sproto schema 失败", ex);
        }
    }

    // ---------- 进程级 schema 缓存 ----------
    //
    // 多角色常驻会话（RoleSessionKeeper）会创建多个 codec 实例，但全进程只读同一对 asset
    // schema；各自解析会各持 ~1MB 重复对象。这里按 byte[] 引用缓存解析结果（解析一次、
    // overrides 应用一次，之后所有 codec 共享只读）。schema 对象加载完成后不再被写，共享安全。

    private static volatile SprotoSchema cachedC2s;
    private static volatile SprotoSchema cachedS2c;
    private static volatile byte[] cachedC2sBytes;
    private static volatile byte[] cachedS2cBytes;

    private static SprotoSchema sharedC2s(byte[] bytes) {
        SprotoSchema parsed = cachedC2s;
        if (parsed != null && sameBytes(cachedC2sBytes, bytes)) return parsed;
        synchronized (PortableSprotoCodec.class) {
            if (cachedC2s == null || !sameBytes(cachedC2sBytes, bytes)) {
                SprotoSchema fresh = new SprotoSchemaParser().parse(bytes);
                LocalSprotoSchemaOverrides.applyC2s(fresh);
                cachedC2s = fresh;
                cachedC2sBytes = bytes;
            }
            return cachedC2s;
        }
    }

    private static SprotoSchema sharedS2c(byte[] bytes) {
        SprotoSchema parsed = cachedS2c;
        if (parsed != null && sameBytes(cachedS2cBytes, bytes)) return parsed;
        synchronized (PortableSprotoCodec.class) {
            if (cachedS2c == null || !sameBytes(cachedS2cBytes, bytes)) {
                cachedS2c = new SprotoSchemaParser().parse(bytes);
                cachedS2cBytes = bytes;
            }
            return cachedS2c;
        }
    }

    private static boolean sameBytes(byte[] cached, byte[] incoming) {
        return cached == incoming || (cached != null && incoming != null && cached.length == incoming.length);
    }

        public byte[] encodeRequest(String proto, Map<String, Object> args, int session) {
        SprotoProtocolDef protocol = this.requireC2s().requireProtocol(proto);
        int cryptMethod = this.outboundCryptMethod(proto);
        byte[] content = protocol.getRequestType() == null ? new byte[]{} : this.encode(protocol.getRequestType(), args);
        content = this.applyMessageCrypt(content, cryptMethod, content.length, this.outboundRc4);
        LinkedHashMap<String, Object> headerValues = new LinkedHashMap<String, Object>();
        headerValues.put("type", protocol.getTag());
        headerValues.put("session", session);
        if (cryptMethod != 0) {
            LinkedHashMap<String, Integer> userdata = new LinkedHashMap<String, Integer>();
            userdata.put("crypt_method", cryptMethod);
            if (protocol.getRequestType() != null) {
                userdata.put("crypt_length", content.length);
            }
            headerValues.put("ud", userdata);
        }
        byte[] header = this.encode(this.requireC2sPackageType(), headerValues);
        if (session > 0) {
            this.registerSession(session, proto, protocol.getResponseType() == null ? NO_RESPONSE : protocol.getResponseType());
        }
        return this.pack(this.concat(header, content));
    }

        public GameMessage decodeResponse(byte[] payload) {
        byte[] unpacked = this.unpack(payload);
        PortableSprotoCodecDecodedValue decodedHeader = this.decode(this.requireS2cPackageType(), unpacked, 0);
        Map<String, Object> header = this.castMap(decodedHeader.value());
        int type = this.intValue(header.get("type"), 0);
        int session = this.intValue(header.get("session"), 0);
        int contentOffset = decodedHeader.nextOffset();
        byte[] content = this.decryptMessageContent(header, Arrays.copyOfRange(unpacked, contentOffset, unpacked.length));
        GameMessage message = new GameMessage();
        message.setLength(payload.length);
        message.setSession(session);
        if (type > 0) {
            SprotoProtocolDef protocol = this.resolveProtocol(type);
            message.setType("REQUEST");
            if (protocol == null) {
                message.setProto("unknown_" + type);
                message.setContent(this.unknownContent(type, session, content.length));
            } else {
                message.setProto(protocol.getName());
                if (protocol.getRequestType() != null && content.length > 0) {
                    message.setContent(this.castMap(this.decode(protocol.getRequestType(), content, 0).value()));
                } else {
                    message.setContent(new LinkedHashMap<String, Object>());
                }
                if (session > 0 && protocol.getResponseType() != null) {
                    if (this.responseSessions.containsKey(session)) {
                        if (this.warnedSessionCollisions.putIfAbsent(session, Boolean.TRUE) == null) {
                            WjdrLog.w("Server packet reuses inflight session tag={} proto={} session={}; keeping original registration", new Object[]{type, protocol.getName(), session});
                        }
                    } else {
                        this.registerSession(session, protocol.getName(), protocol.getResponseType());
                    }
                }
            }
        } else {
            SprotoTypeDef responseType = this.responseSessions.remove(session);
            String protocolName = this.protocolSessions.remove(session);
            this.sessionRegisteredAt.remove(session);
            message.setType("RESPONSE");
            message.setProto(protocolName == null ? "" : protocolName);
            if (responseType != null && responseType != NO_RESPONSE && content.length > 0) {
                message.setContent(this.castMap(this.decode(responseType, content, 0).value()));
            } else if (content.length > 0 && session > 0) {
                message.setContent(this.unmappedResponseContent(session, content.length, protocolName));
                if (this.warnedUnmappedResponses.putIfAbsent(session, Boolean.TRUE) == null) {
                    WjdrLog.w("Decoded response without session mapping session={} size={} proto={}", new Object[]{session, content.length, protocolName});
                }
            } else {
                message.setContent(new LinkedHashMap<String, Object>());
            }
        }
        this.rememberLoginCryptSettings(message);
        return message;
    }

    private void registerSession(int session, String proto, SprotoTypeDef responseType) {
        if (session <= 0) {
            return;
        }
        this.protocolSessions.put(session, proto);
        this.responseSessions.put(session, responseType);
        this.sessionRegisteredAt.put(session, System.currentTimeMillis());
        this.sweepStaleSessions();
    }

    private void sweepStaleSessions() {
        if (this.responseSessions.size() <= 2048) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 600000L;
        this.sessionRegisteredAt.forEach((session, registeredAt) -> {
            if (registeredAt < cutoff) {
                this.releaseSession((int)session);
            }
        });
    }

        public void releaseSession(int session) {
        this.responseSessions.remove(session);
        this.protocolSessions.remove(session);
        this.sessionRegisteredAt.remove(session);
    }

        public void clearSessions() {
        this.responseSessions.clear();
        this.protocolSessions.clear();
        this.sessionRegisteredAt.clear();
        this.warnedSessionCollisions.clear();
        this.warnedUnmappedResponses.clear();
    }

    public Map<String, Object> decodeByTypeName(String typeName, byte[] payload) {
        if (typeName == null || typeName.isBlank()) {
            throw new IllegalArgumentException("sproto \u7c7b\u578b\u540d\u4e0d\u80fd\u4e3a\u7a7a");
        }
        if (payload == null || payload.length == 0) {
            return Map.of();
        }
        byte[] unpacked = this.unpack(payload);
        return this.castMap(this.decode(this.requireS2c().requireType(typeName), unpacked, 0).value());
    }

    public Map<String, Object> decodeByTypeName(String typeName, byte[] payload, Set<String> retainedFields) {
        if (typeName == null || typeName.isBlank()) {
            throw new IllegalArgumentException("sproto \u7c7b\u578b\u540d\u4e0d\u80fd\u4e3a\u7a7a");
        }
        if (payload == null || payload.length == 0) {
            return Map.of();
        }
        byte[] unpacked = this.unpack(payload);
        Set<String> whitelist = retainedFields == null ? null : Set.copyOf(retainedFields);
        return this.castMap(this.decode(this.requireS2c().requireType(typeName), unpacked, 0, whitelist).value());
    }

    private int outboundCryptMethod(String protocolName) {
        int negotiated = this.defaultCryptMethod;
        if (negotiated != 0) {
            return negotiated;
        }
        return XOR_PROTOCOLS.contains(protocolName) ? 1 : 0;
    }

    private byte[] decryptMessageContent(Map<String, Object> header, byte[] content) {
        Map<String, Object> userdata = this.castMap(header.get("ud"));
        int cryptMethod = this.intValue(userdata.get("crypt_method"), 0);
        int cryptLength = this.intValue(userdata.get("crypt_length"), content.length);
        return this.applyMessageCrypt(content, cryptMethod, cryptLength, this.inboundRc4);
    }

    private byte[] applyMessageCrypt(byte[] content, int cryptMethod, int cryptLength, Rc4StreamCodec rc4Stream) {
        if (cryptMethod == 0 || content.length == 0) {
            return content;
        }
        if (cryptMethod == 1) {
            return XorCodec.apply(content, this.secretKey.get());
        }
        if (cryptMethod == 2) {
            return rc4Stream.apply(content, cryptLength);
        }
        throw new IllegalArgumentException("Unsupported message crypt method: " + cryptMethod);
    }

    private void rememberLoginCryptSettings(GameMessage message) {
        byte[] secretBytes;
        if (!"loginauth".equals(message.getProto()) || message.getContent() == null) {
            return;
        }
        Object secret = message.getContent().get("secret_key");
        if (secret instanceof byte[] && (secretBytes = (byte[])secret).length > 0) {
            this.updateSecretKey(secretBytes);
        }
        this.defaultCryptMethod = this.intValue(message.getContent().get("crypt_method"), 0);
    }

    private SprotoProtocolDef resolveProtocol(int tag) {
        return this.requireS2c().findProtocol(tag).or(() -> this.requireC2s().findProtocol(tag)).orElseGet(() -> {
            if (this.warnedUnknownProtocols.putIfAbsent(tag, Boolean.TRUE) == null) {
                WjdrLog.w("Observed unmapped server protocol tag: {}", (Object)tag);
            }
            return null;
        });
    }

    private Map<String, Object> unknownContent(int type, int session, int contentLength) {
        LinkedHashMap<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("_unknown_proto_tag", type);
        content.put("_session", session);
        content.put("_raw_size", contentLength);
        return content;
    }

    private Map<String, Object> unmappedResponseContent(int session, int contentLength, String protocolName) {
        LinkedHashMap<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("_unmapped_session", session);
        content.put("_raw_size", contentLength);
        if (protocolName != null && !protocolName.isBlank()) {
            content.put("_proto", protocolName);
        }
        return content;
    }

        public void updateSecretKey(byte[] secretKey) {
        this.keyUpdateLock.lock();
        try {
            if (secretKey == null || secretKey.length == 0) {
                return;
            }
            byte[] copiedKey = Arrays.copyOf(secretKey, secretKey.length);
            this.secretKey.set(copiedKey);
            this.outboundRc4 = new Rc4StreamCodec(copiedKey);
            this.inboundRc4 = new Rc4StreamCodec(copiedKey);
        }
        finally {
            this.keyUpdateLock.unlock();
        }
    }

        public boolean available() {
        return this.c2sSchema != null && this.s2cSchema != null;
    }

    private byte[] encode(SprotoTypeDef type, Map<String, Object> data) {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int lastTag = -1;
        int recordCount = 0;
        for (SprotoFieldDef field : type.getFields()) {
            Object rawValue;
            PortableSprotoCodecEncodedField encoded = this.encodeField(field, rawValue = data == null ? null : data.get(field.getName()));
            if (!encoded.present()) continue;
            int gap = field.getTag() - lastTag - 1;
            if (gap > 0) {
                int skip = (gap - 1) * 2 + 1;
                this.writeWord(header, skip);
                ++recordCount;
            }
            this.writeWord(header, encoded.headerValue());
            ++recordCount;
            if (encoded.body().length > 0) {
                body.writeBytes(encoded.body());
            }
            lastTag = field.getTag();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        this.writeWord(output, recordCount);
        output.writeBytes(header.toByteArray());
        output.writeBytes(body.toByteArray());
        return output.toByteArray();
    }

    private PortableSprotoCodecEncodedField encodeField(SprotoFieldDef field, Object rawValue) {
        if (rawValue == null) {
            return PortableSprotoCodecEncodedField.absent();
        }
        if (field.isArray()) {
            byte[] payload = this.encodeArray(field, rawValue);
            return new PortableSprotoCodecEncodedField(true, 0, payload);
        }
        int type = field.baseType();
        // Java 8 语法改写原 switch 表达式
        if (type == 0) {
            return this.encodeIntegerField(field, rawValue);
        } else if (type == 1) {
            return this.encodeBooleanField(rawValue);
        } else if (type == 2) {
            return new PortableSprotoCodecEncodedField(true, 0, this.withLength(this.encodeString(field, rawValue)));
        } else if (type == 4) {
            return new PortableSprotoCodecEncodedField(true, 0, this.withLength(this.encodeStruct(field, rawValue)));
        } else if (type == 3) {
            return new PortableSprotoCodecEncodedField(true, 0, this.withLength(this.encodeDouble(rawValue)));
        }
        throw new IllegalArgumentException("Unsupported sproto field type: " + type);
    }

    private PortableSprotoCodecEncodedField encodeIntegerField(SprotoFieldDef field, Object rawValue) {
        long value = this.normalizeInteger(field, rawValue);
        int int32 = (int)value;
        long vh = value >> 31;
        if ((vh == 0L || vh == -1L) && Integer.toUnsignedLong(int32) < 32767L) {
            return new PortableSprotoCodecEncodedField(true, (int32 + 1) * 2, new byte[0]);
        }
        byte[] bytes = vh == 0L || vh == -1L ? this.encodeInt32(int32) : this.encodeInt64(value);
        return new PortableSprotoCodecEncodedField(true, 0, this.withLength(bytes));
    }

    private PortableSprotoCodecEncodedField encodeBooleanField(Object rawValue) {
        int intValue = this.truthy(rawValue) ? 1 : 0;
        return new PortableSprotoCodecEncodedField(true, (intValue + 1) * 2, new byte[0]);
    }

    private byte[] encodeArray(SprotoFieldDef field, Object rawValue) {
        List<Object> values = this.normalizeArray(rawValue);
        int baseType = field.baseType();
        // Java 8 语法改写原 switch 表达式
        if (baseType == 0) {
            return this.encodeIntegerArray(field, values);
        } else if (baseType == 1) {
            return this.encodeBooleanArray(values);
        } else if (baseType == 2) {
            return this.encodeObjectArray(values, item -> this.encodeString(field, item));
        } else if (baseType == 4) {
            return this.encodeObjectArray(values, item -> this.encodeStruct(field, item));
        } else if (baseType == 3) {
            return this.encodeDoubleArray(values);
        }
        throw new IllegalArgumentException("Unsupported array field type: " + baseType);
    }

    private byte[] encodeIntegerArray(SprotoFieldDef field, List<Object> values) {
        if (values.isEmpty()) {
            return this.withLength(new byte[0]);
        }
        ArrayList<byte[]> encoded = new ArrayList<byte[]>();
        boolean use64 = false;
        for (Object value : values) {
            long normalized = this.normalizeInteger(field, value);
            long vh = normalized >> 31;
            if (vh != 0L && vh != -1L) {
                use64 = true;
            }
            encoded.add(vh == 0L || vh == -1L ? this.encodeInt32((int)normalized) : this.encodeInt64(normalized));
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(use64 ? 8 : 4);
        for (byte[] item : encoded) {
            if (use64 && item.length == 4) {
                int intValue = ByteBuffer.wrap(item).order(ByteOrder.LITTLE_ENDIAN).getInt();
                payload.writeBytes(this.encodeInt64(intValue));
                continue;
            }
            if (!use64 && item.length == 8) {
                long longValue = ByteBuffer.wrap(item).order(ByteOrder.LITTLE_ENDIAN).getLong();
                payload.writeBytes(this.encodeInt32((int)longValue));
                continue;
            }
            payload.writeBytes(item);
        }
        return this.withLength(payload.toByteArray());
    }

    private byte[] encodeBooleanArray(List<Object> values) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (Object value : values) {
            payload.write(this.truthy(value) ? 1 : 0);
        }
        return this.withLength(payload.toByteArray());
    }

    private byte[] encodeDoubleArray(List<Object> values) {
        if (values.isEmpty()) {
            return this.withLength(new byte[0]);
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(8);
        for (Object value : values) {
            payload.writeBytes(this.encodeDouble(value));
        }
        return this.withLength(payload.toByteArray());
    }

    private byte[] encodeObjectArray(List<Object> values, ObjectEncoder encoder) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (Object value : values) {
            byte[] item = encoder.encode(value);
            payload.writeBytes(this.withLength(item));
        }
        return this.withLength(payload.toByteArray());
    }

    private byte[] encodeString(SprotoFieldDef field, Object rawValue) {
        if (field.getExtra() == 1) {
            if (rawValue instanceof byte[]) {
                byte[] bytes = (byte[])rawValue;
                return bytes;
            }
            throw new IllegalArgumentException("Binary field requires byte[]: " + field.getName());
        }
        return String.valueOf(rawValue).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] encodeStruct(SprotoFieldDef field, Object rawValue) {
        Map<String, Object> value = this.castMap(rawValue);
        return this.encode(field.getSubtype(), value);
    }

    private byte[] encodeDouble(Object rawValue) {
        double value = this.numericDouble(rawValue);
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array();
    }

    private PortableSprotoCodecDecodedValue decode(SprotoTypeDef type, byte[] bytes, int offset) {
        return this.decode(type, bytes, offset, null);
    }

    private PortableSprotoCodecDecodedValue decode(SprotoTypeDef type, byte[] bytes, int offset, Set<String> retainedFields) {
        int fn = this.word(bytes, offset);
        int streamOffset = offset + 2;
        int dataOffset = streamOffset + fn * 2;
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        int tag = -1;
        for (int i = 0; i < fn; ++i) {
            boolean retained;
            int record = this.word(bytes, streamOffset + i * 2);
            ++tag;
            if ((record & 1) == 1) {
                tag += record / 2;
                continue;
            }
            int value = record / 2 - 1;
            SprotoFieldDef field = type.findField(tag);
            boolean bl = retained = field != null && (retainedFields == null || retainedFields.isEmpty() || retainedFields.contains(field.getName()));
            if (value < 0) {
                int size = SprotoBinarySupport.readInt32(bytes, dataOffset);
                int blockOffset = dataOffset + 4;
                if (retained) {
                    Object decoded = this.decodeField(field, bytes, blockOffset, size);
                    this.storeDecodedValue(result, field, decoded);
                }
                dataOffset += 4 + size;
                continue;
            }
            if (!retained) continue;
            Object decoded = this.decodeInline(field, value);
            this.storeDecodedValue(result, field, decoded);
        }
        return new PortableSprotoCodecDecodedValue(result, dataOffset);
    }

    private Object decodeField(SprotoFieldDef field, byte[] bytes, int blockOffset, int size) {
        if (field.isArray()) {
            return this.decodeArray(field, bytes, blockOffset, size);
        }
        int t = field.baseType();
        if (t == 0) return this.decodeInteger(bytes, blockOffset, size, field.getExtra());
        if (t == 1) return size == 0 ? Boolean.FALSE : bytes[blockOffset] != 0;
        if (t == 2) return this.decodeString(field, bytes, blockOffset, size);
        if (t == 4) return this.decode(field.getSubtype(), bytes, blockOffset).value();
        if (t == 3) return ByteBuffer.wrap(bytes, blockOffset, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
        throw new IllegalArgumentException("Unsupported field type: " + field.baseType());
    }

    private Object decodeInline(SprotoFieldDef field, int value) {
        int t = field.baseType();
        if (t == 0) return Double.valueOf(field.getExtra() > 0 ? (double) value / (double) field.getExtra() : (double) value);
        if (t == 1) return value != 0;
        throw new IllegalArgumentException("Inline value not supported for type: " + field.baseType());
    }

    private Object decodeArray(SprotoFieldDef field, byte[] bytes, int blockOffset, int size) {
        if (size == 0) {
            return field.getKey() >= 0 ? new LinkedHashMap() : new ArrayList();
        }
        int t = field.baseType();
        if (t == 0) return this.decodeIntegerArray(field, bytes, blockOffset, size);
        if (t == 1) return this.decodeBooleanArray(bytes, blockOffset, size);
        if (t == 2) return this.decodeObjectArray(field, bytes, blockOffset, size, false);
        if (t == 4) return this.decodeObjectArray(field, bytes, blockOffset, size, true);
        if (t == 3) return this.decodeDoubleArray(bytes, blockOffset, size);
        throw new IllegalArgumentException("Unsupported array type: " + field.baseType());
    }

    private Object decodeIntegerArray(SprotoFieldDef field, byte[] bytes, int offset, int size) {
        if (size == 0) {
            return new ArrayList();
        }
        int len = bytes[offset] & 0xFF;
        int payloadOffset = offset + 1;
        int payloadSize = size - 1;
        ArrayList<Double> result = new ArrayList<Double>();
        if (len == 4) {
            for (int i = 0; i < payloadSize / 4; ++i) {
                int value = SprotoBinarySupport.readInt32(bytes, payloadOffset + i * 4);
                long expanded = this.expand64(value);
                result.add(field.getExtra() > 0 ? (double)expanded / (double)field.getExtra() : (double)expanded);
            }
            return result;
        }
        if (len == 8) {
            for (int i = 0; i < payloadSize / 8; ++i) {
                long value = ByteBuffer.wrap(bytes, payloadOffset + i * 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                result.add(field.getExtra() > 0 ? (double)value / (double)field.getExtra() : (double)value);
            }
            return result;
        }
        throw new IllegalArgumentException("Invalid integer array item length: " + len);
    }

    private List<Object> decodeBooleanArray(byte[] bytes, int offset, int size) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (int i = 0; i < size; ++i) {
            result.add(bytes[offset + i] != 0);
        }
        return result;
    }

    private Object decodeObjectArray(SprotoFieldDef field, byte[] bytes, int offset, int size, boolean structType) {
        int cursor = offset;
        int end = offset + size;
        if (field.getKey() >= 0 && structType) {
            int itemSize;
            LinkedHashMap<Object, Map<String, Object>> result = new LinkedHashMap<Object, Map<String, Object>>();
            for (cursor = offset; cursor < end; cursor += itemSize) {
                itemSize = SprotoBinarySupport.readInt32(bytes, cursor);
                PortableSprotoCodecDecodedValue decoded = this.decode(field.getSubtype(), bytes, cursor += 4);
                Map<String, Object> value = this.castMap(decoded.value());
                Object key = this.findKeyByTag(field.getSubtype(), value, field.getKey());
                result.put(key, value);
            }
            return result;
        }
        ArrayList<Object> result = new ArrayList<Object>();
        while (cursor < end) {
            int itemSize = SprotoBinarySupport.readInt32(bytes, cursor);
            cursor += 4;
            if (structType) {
                result.add(this.decode(field.getSubtype(), bytes, cursor).value());
            } else {
                result.add(this.decodeString(field, bytes, cursor, itemSize));
            }
            cursor += itemSize;
        }
        return result;
    }

    private List<Object> decodeDoubleArray(byte[] bytes, int offset, int size) {
        ArrayList<Object> result = new ArrayList<Object>();
        if (size == 0) {
            return result;
        }
        int len = bytes[offset] & 0xFF;
        int end = offset + size;
        if (len != 8) {
            throw new IllegalArgumentException("Invalid double array item length: " + len);
        }
        for (int cursor = offset + 1; cursor < end; cursor += 8) {
            result.add(ByteBuffer.wrap(bytes, cursor, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble());
        }
        return result;
    }

    private Object decodeInteger(byte[] bytes, int offset, int size, int extra) {
        long value;
        if (size == 4) {
            value = this.expand64(SprotoBinarySupport.readInt32(bytes, offset));
        } else if (size == 8) {
            value = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        } else {
            throw new IllegalArgumentException("Unexpected integer length: " + size);
        }
        return extra > 0 ? (double)value / (double)extra : (double)value;
    }

    private Object decodeString(SprotoFieldDef field, byte[] bytes, int offset, int size) {
        byte[] raw = Arrays.copyOfRange(bytes, offset, offset + size);
        if (field.getExtra() == 1) {
            return raw;
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private void storeDecodedValue(Map<String, Object> result, SprotoFieldDef field, Object decoded) {
        if (decoded == null) {
            return;
        }
        result.put(field.getName(), decoded);
    }

    public byte[] pack(byte[] src) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tmp = new byte[8];
        int ffCount = 0;
        int ffStartSrc = -1;
        int ffStartOut = -1;
        for (int i = 0; i < src.length; i += 8) {
            Arrays.fill(tmp, (byte)0);
            int remain = Math.min(8, src.length - i);
            System.arraycopy(src, i, tmp, 0, remain);
            PortableSprotoCodecPackedSegment segment = this.packSegment(tmp, ffCount);
            if (segment.isFfStart()) {
                ffStartSrc = i;
                ffStartOut = out.size();
                ffCount = 1;
                out.writeBytes(segment.bytes());
                continue;
            }
            if (segment.isFfContinue()) {
                if (++ffCount != 256) continue;
                this.rewriteFF(src, out, ffStartSrc, ffStartOut, ffCount);
                ffCount = 0;
                continue;
            }
            if (ffCount > 0) {
                this.rewriteFF(src, out, ffStartSrc, ffStartOut, ffCount);
                ffCount = 0;
            }
            out.writeBytes(segment.bytes());
        }
        if (ffCount > 0) {
            this.rewriteFF(src, out, ffStartSrc, ffStartOut, ffCount);
        }
        return out.toByteArray();
    }

    public byte[] unpack(byte[] src) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < src.length) {
            int header;
            if ((header = src[offset++] & 0xFF) == 255) {
                int n;
                if (offset >= src.length) {
                    throw new IllegalArgumentException("Invalid packed block");
                }
                if (offset + (n = ((src[offset++] & 0xFF) + 1) * 8) > src.length) {
                    throw new IllegalArgumentException("Invalid packed ff block");
                }
                out.writeBytes(Arrays.copyOfRange(src, offset, offset + n));
                offset += n;
                continue;
            }
            for (int i = 0; i < 8; ++i) {
                int nz = header >> i & 1;
                if (nz == 1) {
                    if (offset >= src.length) {
                        throw new IllegalArgumentException("Invalid packed segment");
                    }
                    out.write(src[offset++]);
                    continue;
                }
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    private PortableSprotoCodecPackedSegment packSegment(byte[] src, int ffCount) {
        int notZero = 0;
        int header = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        for (int i = 0; i < 8; ++i) {
            if (src[i] == 0) continue;
            ++notZero;
            header |= 1 << i;
            out.write(src[i]);
        }
        if ((notZero == 7 || notZero == 6) && ffCount > 0) {
            notZero = 8;
        }
        if (notZero == 8) {
            if (ffCount > 0) {
                return PortableSprotoCodecPackedSegment.continueFf();
            }
            return PortableSprotoCodecPackedSegment.startFf(new byte[10]);
        }
        byte[] bytes = out.toByteArray();
        bytes[0] = (byte)header;
        return PortableSprotoCodecPackedSegment.normal(bytes);
    }

    private void rewriteFF(byte[] src, ByteArrayOutputStream out, int srcStart, int outStart, int count) {
        byte[] current = out.toByteArray();
        int copyLength = count * 8;
        byte[] replacement = new byte[2 + copyLength];
        replacement[0] = -1;
        replacement[1] = (byte)(count - 1);
        System.arraycopy(src, srcStart, replacement, 2, Math.min(copyLength, src.length - srcStart));
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        rewritten.writeBytes(Arrays.copyOfRange(current, 0, outStart));
        rewritten.writeBytes(replacement);
        if (outStart + 10 < current.length) {
            rewritten.writeBytes(Arrays.copyOfRange(current, outStart + 10, current.length));
        }
        out.reset();
        out.writeBytes(rewritten.toByteArray());
    }

    private List<Object> normalizeArray(Object value) {
        // Java 8 改写：原 invokedynamic typeSwitch
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List) {
            return new ArrayList<>((List<?>) value);
        }
        if (value instanceof Collection) {
            return new ArrayList<>((Collection<?>) value);
        }
        if (value instanceof Map) {
            return new ArrayList<>(((Map<?, ?>) value).values());
        }
        throw new IllegalArgumentException("Array field expects Collection or Map");
    }

    private long normalizeInteger(SprotoFieldDef field, Object rawValue) {
        Objects.requireNonNull(rawValue, "Integer field value cannot be null");
        if (field.getExtra() > 0) {
            double value = this.numericDouble(rawValue);
            return Math.round(value * (double)field.getExtra());
        }
        if (rawValue instanceof Number) {
            Number number = (Number)rawValue;
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(rawValue));
    }

    private double numericDouble(Object rawValue) {
        double d;
        if (rawValue instanceof Number) {
            Number number = (Number)rawValue;
            d = number.doubleValue();
        } else {
            d = Double.parseDouble(String.valueOf(rawValue));
        }
        return d;
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean) {
            Boolean bool = (Boolean)value;
            return bool;
        }
        if (value instanceof Number) {
            Number number = (Number)value;
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private byte[] encodeInt32(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private byte[] encodeInt64(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    private byte[] withLength(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        this.writeDword(out, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private void writeWord(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write(value >> 8 & 0xFF);
    }

    private void writeDword(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write(value >> 8 & 0xFF);
        out.write(value >> 16 & 0xFF);
        out.write(value >> 24 & 0xFF);
    }

    private int word(byte[] data, int offset) {
        return data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8;
    }

    private long expand64(int value) {
        long result = Integer.toUnsignedLong(value);
        if ((result & 0x80000000L) != 0L) {
            result |= 0xFFFFFFFF00000000L;
        }
        return result;
    }

    private Map<String, Object> castMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (value instanceof Map) {
            Map map = (Map)value;
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        throw new IllegalArgumentException("Struct field expects Map");
    }

    private byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number) {
            Number number = (Number)value;
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Object findKeyByTag(SprotoTypeDef type, Map<String, Object> value, int tag) {
        SprotoFieldDef keyField = type.findField(tag);
        return keyField == null ? null : value.get(keyField.getName());
    }

    private SprotoSchema requireC2s() {
        if (this.c2sSchema == null) {
            throw new IllegalStateException("c2s schema not loaded");
        }
        return this.c2sSchema;
    }

    private SprotoSchema requireS2c() {
        if (this.s2cSchema == null) {
            throw new IllegalStateException("s2c schema not loaded");
        }
        return this.s2cSchema;
    }

    private SprotoTypeDef requireC2sPackageType() {
        if (this.c2sPackageType == null) {
            throw new IllegalStateException("c2s package type not loaded");
        }
        return this.c2sPackageType;
    }

    private SprotoTypeDef requireS2cPackageType() {
        if (this.s2cPackageType == null) {
            throw new IllegalStateException("s2c package type not loaded");
        }
        return this.s2cPackageType;
    }

    private static interface ObjectEncoder {
        public byte[] encode(Object var1);
    }
}

