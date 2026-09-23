package com.carlos.vault.license;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * 票据的本地持久化（SharedPreferences <code>license_vault</code>）。
 *
 * 读取时用 {@link LicenseCrypto#ticketKey} 重算 HMAC 并与落盘签名比对：
 * 不符即视为 {@link LicenseState#TAMPERED}（等同未激活）。
 *
 * 写入只在 activate / verify / rebind 成功后发生，调用方需保证串行
 * （{@link LicenseManager} 内部用单线程锁串行化，避免并发写坏票据）。
 */
public class LicenseStore {

    /** 读取结果：区分「没有票据」与「有票据但被篡改」。 */
    public static class LoadResult {
        /** 校验通过的票据；null 表示不可用。 */
        public final LicenseTicket ticket;
        /** 是否读到了票据内容但签名不符。 */
        public final boolean tampered;
        /** 是否读到了票据内容（无论是否通过校验）。 */
        public final boolean present;

        LoadResult(LicenseTicket ticket, boolean tampered, boolean present) {
            this.ticket = ticket;
            this.tampered = tampered;
            this.present = present;
        }
    }

    private final Context ctx;
    private final SharedPreferences sp;

    public LicenseStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(LicenseConfig.SP_NAME, Context.MODE_PRIVATE);
    }

    // ---------- 票据 ----------

    /** 读取并校验票据。任何异常都退化为「无票据」。 */
    public LoadResult load() {
        String json;
        String sig;
        try {
            json = sp.getString(LicenseConfig.KEY_TICKET_JSON, null);
            sig = sp.getString(LicenseConfig.KEY_TICKET_SIG, null);
        } catch (Throwable t) {
            return new LoadResult(null, false, false);
        }
        if (json == null || json.trim().isEmpty()) {
            return new LoadResult(null, false, false);
        }
        LicenseTicket ticket = LicenseTicket.fromJson(json);
        if (ticket == null || ticket.isEmpty()) {
            return new LoadResult(null, false, false);
        }
        if (sig == null || !sig.equals(signTicket(ticket))) {
            // 内容存在但签名不符 —— 被改过，或换签名重打包导致 ticketKey 变化。
            return new LoadResult(null, true, true);
        }
        return new LoadResult(ticket, false, true);
    }

    /** 计算票据签名。 */
    public String signTicket(LicenseTicket ticket) {
        return LicenseCrypto.hmacSha256Hex(
                LicenseCrypto.ticketKey(ctx), ticket.canonicalForSign());
    }

    /** 覆盖写入票据（同时刷新签名与 last_verify_at 冗余键）。 */
    public void save(LicenseTicket ticket) {
        if (ticket == null) return;
        try {
            ticket.sig = signTicket(ticket);
            sp.edit()
                    .putString(LicenseConfig.KEY_TICKET_JSON, ticket.toJson())
                    .putString(LicenseConfig.KEY_TICKET_SIG, ticket.sig)
                    .putLong(LicenseConfig.KEY_LAST_VERIFY_AT, ticket.lastVerifiedAt)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 清空票据（换码/重置时用）。设备 UUID 刻意保留，避免重装后频繁换绑。 */
    public void clear() {
        try {
            sp.edit()
                    .remove(LicenseConfig.KEY_TICKET_JSON)
                    .remove(LicenseConfig.KEY_TICKET_SIG)
                    .remove(LicenseConfig.KEY_LAST_VERIFY_AT)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 最后一次联网校验成功时间（冗余快照，便于不做完整加载也能取到）。 */
    public long lastVerifiedAt() {
        try {
            return sp.getLong(LicenseConfig.KEY_LAST_VERIFY_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ---------- 设备 UUID 回退（静态，供 DeviceId 直接调用，避免循环依赖） ----------

    /** 读取持久化的设备 UUID；不存在返回 null。 */
    public static String getDeviceUuid(Context ctx) {
        try {
            SharedPreferences p = ctx.getApplicationContext()
                    .getSharedPreferences(LicenseConfig.SP_NAME, Context.MODE_PRIVATE);
            return p.getString(LicenseConfig.KEY_DEVICE_UUID, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 持久化设备 UUID。 */
    public static void setDeviceUuid(Context ctx, String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return;
        try {
            SharedPreferences p = ctx.getApplicationContext()
                    .getSharedPreferences(LicenseConfig.SP_NAME, Context.MODE_PRIVATE);
            p.edit().putString(LicenseConfig.KEY_DEVICE_UUID, uuid).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 生成并持久化一个新的设备 UUID（仅测试/排障用）。 */
    public static String rollDeviceUuid(Context ctx) {
        String uuid = UUID.randomUUID().toString();
        setDeviceUuid(ctx, uuid);
        return uuid;
    }
}
