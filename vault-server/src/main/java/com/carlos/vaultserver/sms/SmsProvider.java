package com.carlos.vaultserver.sms;

/**
 * 接码平台统一抽象：取号 / 收码 / 释放 / 拉黑 / 连接自检。
 *
 * <p>当前实现：{@link D1SmsProvider}（d1jiema）与 {@link HaozhuSmsProvider}（haozhuma），
 * 由 {@link SmsProviders} 工厂按配置创建。轮询节奏由调用方控制（本包不内置线程）；
 * 单测全部注入假 Transport，不真实外呼。
 */
public interface SmsProvider {

    /** 平台标识：{@code "d1"} / {@code "haozhuma"}。 */
    String name();

    /**
     * 连接自检：只验证凭据+余额，不取号。
     * 任何失败（上游错误/网络失败/响应非法）返回 ok=false 且 error 带原因，不抛异常。
     */
    ProviderStatus testConnection();

    /**
     * 取号。keyword 语义随平台：D1 = 短信关键词 keyWord；豪猪忽略（项目由 sid 绑定，可为空）。
     * 成功返回 {@link PhoneOrder}；上游响应解析不出号码时抛 {@link SmsException}。
     */
    PhoneOrder acquireNumber(String keyword) throws SmsException;

    /**
     * 取一次验证码（是否继续轮询由调用方决定）。短信未到返回 waiting=true 的 {@link SmsMessage}，
     * 不抛异常；其余失败抛 {@link SmsException}。
     */
    SmsMessage fetchSms(PhoneOrder order, String keyword) throws SmsException;

    /** 释放号码（归还平台，可再分配）。 */
    void release(PhoneOrder order) throws SmsException;

    /** 拉黑号码（不再分配给本账号）。 */
    void block(PhoneOrder order) throws SmsException;

    /** 连接自检结果；ok=false 时 error 必有文本。maxNumbers 平台不返回时为 -1。 */
    final class ProviderStatus {
        public final boolean ok;
        public final String balanceText;
        public final int maxNumbers;
        /** 掩码后的凭据（D1=token、豪猪=账号），日志/UI 可直接使用。 */
        public final String credentialMask;
        public final String error;

        private ProviderStatus(boolean ok, String balanceText, int maxNumbers,
                String credentialMask, String error) {
            this.ok = ok;
            this.balanceText = nullToEmpty(balanceText);
            this.maxNumbers = maxNumbers;
            this.credentialMask = nullToEmpty(credentialMask);
            this.error = nullToEmpty(error);
        }

        public static ProviderStatus ok(String balanceText, int maxNumbers, String credentialMask) {
            return new ProviderStatus(true, balanceText, maxNumbers, credentialMask, "");
        }

        public static ProviderStatus fail(String credentialMask, String error) {
            return new ProviderStatus(false, "", -1, credentialMask, error);
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }
    }

    /** 号码订单：phone=号码；providerData=平台附加数据（如豪猪 order 标识；无则空串）。 */
    final class PhoneOrder {
        public final String phone;
        public final String providerData;

        public PhoneOrder(String phone, String providerData) {
            this.phone = phone == null ? "" : phone;
            this.providerData = providerData == null ? "" : providerData;
        }
    }

    /** 单次取码结果：waiting=true 表示短信未到（code/content 为空），轮询方继续等待。 */
    final class SmsMessage {
        public final boolean waiting;
        public final String code;
        /** 短信全文（平台未返回时为空串）。 */
        public final String content;
        /** 上游响应原文（宽容解析的回溯依据）。 */
        public final String raw;

        public SmsMessage(boolean waiting, String code, String content, String raw) {
            this.waiting = waiting;
            this.code = code == null ? "" : code;
            this.content = content == null ? "" : content;
            this.raw = raw == null ? "" : raw;
        }

        public static SmsMessage waiting(String raw) {
            return new SmsMessage(true, "", "", raw);
        }

        public static SmsMessage of(String code, String content, String raw) {
            return new SmsMessage(false, code, content, raw);
        }
    }
}
