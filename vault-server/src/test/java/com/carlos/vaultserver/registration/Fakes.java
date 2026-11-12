package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.passport.PassportClient;
import com.carlos.vaultserver.sms.SmsException;
import com.carlos.vaultserver.sms.SmsProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * registration 单测共享假件：脚本回放 passport（真实 {@link PassportClient} + 假 Transport，
 * 与 PassportClientTest 同思路——签名/解析/冷却全真，只换网络层）、全内存 fake 接码平台、
 * 跳时 sleeper。
 */
final class Fakes {

    private Fakes() {
    }

    /** 跳时 sleeper：记录每次请求的毫秒数，不真睡。 */
    static final RecordingSleeper NO_SLEEP = new RecordingSleeper();

    static final class RecordingSleeper implements RegistrationService.Sleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    /**
     * 脚本回放 passport：HTTP 响应按序弹出，耗尽后返回 {@code "{}"}（无凭据 = 失败分支）。
     * 走真实 PassportClient（签名/解析/冷却全真），只把网络层换成脚本，不碰真实网络。
     */
    static PassportFacade scriptedPassport(String... responses) {
        ConcurrentLinkedQueue<String> script = new ConcurrentLinkedQueue<>();
        for (String r : responses) script.add(r);
        PassportClient.Transport scripted = (url, headers, form) -> {
            String next = script.poll();
            return next == null ? "{}" : next;
        };
        return PassportFacade.of(new PassportClient(null, scripted));
    }

    /** 表单捕获版 scriptedPassport：额外把每次请求表单收进 sink（断言提交的验证码等参数）。 */
    static PassportFacade capturingPassport(List<Map<String, String>> sink, String... responses) {
        ConcurrentLinkedQueue<String> script = new ConcurrentLinkedQueue<>();
        for (String r : responses) script.add(r);
        PassportClient.Transport capturing = (url, headers, form) -> {
            sink.add(new LinkedHashMap<>(form));
            String next = script.poll();
            return next == null ? "{}" : next;
        };
        return PassportFacade.of(new PassportClient(null, capturing));
    }

    /** 网络故障版 passport：Transport 抛 IOException（断言网络失败分支）。 */
    static PassportFacade brokenPassport() {
        PassportClient.Transport broken = (url, headers, form) -> {
            throw new IOException("connection reset");
        };
        return PassportFacade.of(new PassportClient(null, broken));
    }

    /** 全内存 fake 接码平台。 */
    static final class FakeSms implements SmsProvider {
        final List<String> phones;
        final List<String> codes;
        /** 每号先返回 waiting 的次数（Integer.MAX_VALUE = 永不到码 → 超时分支）。 */
        int waitingRounds;
        /**
         * 号池残留的旧验证码（模拟真实轮：接码平台复用号码，池中有历史短信）。
         * 非空时：取号后的基线快照与首次收码都会返回该旧码，实现须跳过它只认新码。
         */
        String staleCode = "";
        /** 非空时 acquireNumber 抛出（取号失败分支）。 */
        RuntimeException acquireError;
        /** 可空门闩：每次取号时 countdown（cancel 场景同步用）。 */
        CountDownLatch onAcquire;
        /** 可空门闩：第一次 fetchSms 到达时 countdown，并等待 gate 放行（cancel 场景同步用）。 */
        CountDownLatch fetchReached;
        CountDownLatch fetchGate;

        final List<String> acquired = new ArrayList<>();
        final List<String> released = new ArrayList<>();
        final List<String> blocked = new ArrayList<>();
        private final Map<Integer, Integer> fetchesByPhone = new HashMap<>();

        FakeSms(List<String> phones, List<String> codes) {
            this.phones = phones;
            this.codes = codes;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public ProviderStatus testConnection() {
            return ProviderStatus.ok("9.99", -1, "f***ke");
        }

        @Override
        public PhoneOrder acquireNumber(String keyword) throws SmsException {
            if (onAcquire != null) onAcquire.countDown();
            if (acquireError != null) throw acquireError;
            int i = acquired.size();
            String phone = phones.get(Math.min(i, phones.size() - 1));
            acquired.add(phone);
            return new PhoneOrder(phone, "");
        }

        @Override
        public SmsMessage fetchSms(PhoneOrder order, String keyword) throws SmsException {
            int idx = Math.max(0, acquired.indexOf(order.phone));
            int n = fetchesByPhone.merge(idx, 1, Integer::sum);
            // n == 1 是取号后的「基线快照」，此时返回号池残留态：
            //   有 staleCode 就是旧码（基线记下它，后续同码一律跳过）；
            //   否则按无旧码（waiting）处理。
            if (n == 1) {
                return staleCode == null || staleCode.isEmpty()
                        ? SmsMessage.waiting("{}")
                        : SmsMessage.of(staleCode, "旧短信 您的验证码 " + staleCode, "{}");
            }
            int effective = n - 1;   // 基线之后的第几次收码
            if (fetchReached != null && effective == 1) {
                fetchReached.countDown();
                if (fetchGate != null) {
                    try {
                        fetchGate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            // 旧码残留：在 waitingRounds 之外还会额外返回一次旧码（模拟真实轮观测到的现象）
            if (staleCode != null && !staleCode.isEmpty() && effective == 1) {
                return SmsMessage.of(staleCode, "旧短信 您的验证码 " + staleCode, "{}");
            }
            if (effective <= waitingRounds) return SmsMessage.waiting("{}");
            String code = codes.get(Math.min(idx, codes.size() - 1));
            return SmsMessage.of(code, "您的验证码 " + code + "，勿泄露", "{}");
        }

        @Override
        public void release(PhoneOrder order) throws SmsException {
            released.add(order.phone);
        }

        @Override
        public void block(PhoneOrder order) throws SmsException {
            blocked.add(order.phone);
        }

        /** 固定单号单码便捷构造。 */
        static FakeSms single(String phone, String code) {
            return new FakeSms(new ArrayList<>(List.of(phone)), new ArrayList<>(List.of(code)));
        }
    }

    /** 恒定返回同一 provider 的工厂（批次测试：同实例序列化号码/验证码）。 */
    static RegistrationService.SmsProviderFactory factory(SmsProvider p) {
        return (type, config) -> p;
    }

    /** 标准批次配置（测试基线：d1 + fake token + 30s 收码预算）。 */
    static RegistrationService.PhoneBatchConfig cfg(int goal, int max) {
        RegistrationService.PhoneBatchConfig c = new RegistrationService.PhoneBatchConfig();
        c.providerType = "d1";
        c.providerConfig = Map.of("token", "fake-token");
        c.keyword = "测试";
        c.goalCount = goal;
        c.maxNumbers = max;
        c.smsWaitSeconds = 30;
        c.intervalMillis = 5_000L;
        return c;
    }
}
