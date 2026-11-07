package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.passport.DeviceProfile;
import com.carlos.vaultserver.passport.PassportClient;

/**
 * passport 四通道门面接口：把 {@link PassportClient} 的游客注册 / 实名 / 发码 / 验证码登录
 * 四个方法包成可注入接口，供 {@link RegistrationService} 依赖。
 *
 * <p>设计动机（任务书 §4）：单测注入 fake 实现按脚本回放，绝不碰真实网络 Transport；
 * 生产用 {@link #of(PassportClient)} 适配器直接包住真实客户端，方法签名与
 * {@code PassportClient} 完全一致。异常语义同 {@code PassportClient}：
 * 网络/解析失败抛 {@code PassportException}，参数非法抛 {@code IllegalArgumentException}。
 */
public interface PassportFacade {

    /** 游客快速注册：仅公共参数 + 设备指纹，返回 fpid + session_key。 */
    PassportClient.PassportResult guestRegister(DeviceProfile profile);

    /** 实名认证：本地前置校验（姓名非空 / 身份证 18 位）不合法抛 IllegalArgumentException。 */
    PassportClient.PassportResult realName(String fpid, String sessionKey, String name, String idCard);

    /** 发送短信验证码（60 秒/号冷却由 PassportClient 维护）。 */
    PassportClient.PassportResult sendSms(String mobile, String countryCode, DeviceProfile profile);

    /** 手机号验证码登录：mobile + code → fpid + session_key。 */
    PassportClient.PassportResult smsLogin(String mobile, String code, DeviceProfile profile);

    /** 生产适配器：直接包住 {@link PassportClient} 实例（四方法逐字透传）。 */
    static PassportFacade of(PassportClient client) {
        return new PassportFacade() {
            @Override
            public PassportClient.PassportResult guestRegister(DeviceProfile profile) {
                return client.guestRegister(profile);
            }

            @Override
            public PassportClient.PassportResult realName(String fpid, String sessionKey,
                    String name, String idCard) {
                return client.realName(fpid, sessionKey, name, idCard);
            }

            @Override
            public PassportClient.PassportResult sendSms(String mobile, String countryCode,
                    DeviceProfile profile) {
                return client.sendSms(mobile, countryCode, profile);
            }

            @Override
            public PassportClient.PassportResult smsLogin(String mobile, String code,
                    DeviceProfile profile) {
                return client.smsLogin(mobile, code, profile);
            }
        };
    }
}
