/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.stereotype.Service
 *  tools.jackson.databind.ObjectMapper
 */
package com.wjdr.tool.modules.registration.service;

import com.wjdr.tool.core.account.AccountCredentialCipher;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import com.wjdr.tool.modules.registration.model.PhoneRegistrationLogLevel;
import com.wjdr.tool.modules.registration.model.PhoneRegistrationStage;
import com.wjdr.tool.modules.registration.model.PhoneRegistrationStatus;
import com.wjdr.tool.modules.registration.model.RegistrationProviderType;
import com.wjdr.tool.modules.registration.persistence.PhoneRegistrationRepository;
import com.wjdr.tool.modules.registration.persistence.entity.PhoneRegistrationBatchEntity;
import com.wjdr.tool.modules.registration.persistence.entity.PhoneRegistrationLogEntity;
import com.wjdr.tool.modules.registration.service.PhoneRegistrationPassportGateway;
import com.wjdr.tool.modules.registration.service.RegistrationProviderClient;
import com.wjdr.tool.modules.registration.service.RegistrationSecretCipher;
import com.wjdr.tool.modules.registration.service.RegistrationSleeper;
import java.lang.invoke.LambdaMetafactory;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class PhoneRegistrationService {
    public static final String PROJECT_NAME = "\u70b9\u70b9\u4e92\u52a8";
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final int HARD_MAX_ATTEMPTS = 10;
    static final long SMS_POLL_INTERVAL_MILLIS = 15000L;
    static final int SMS_POLL_ATTEMPTS = 12;
    private static final long STALE_BATCH_SECONDS = 120L;
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^\\d{17}[\\dXx]$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PhoneRegistrationRepository repository;
    private final RegistrationProviderClient providerClient;
    private final PhoneRegistrationPassportGateway passportGateway;
    private final RegistrationSecretCipher secretCipher;
    private final Executor executor;
    private final RegistrationSleeper sleeper;
    private final Clock clock;

    @Autowired
    public PhoneRegistrationService(PhoneRegistrationRepository repository, RegistrationProviderClient providerClient, PhoneRegistrationPassportGateway passportGateway, RegistrationSecretCipher secretCipher) {
        this(repository, providerClient, passportGateway, secretCipher, (Executor)LambdaMetafactory.metafactory(null, null, null, (Ljava/lang/Runnable;)V, startVirtualThread(java.lang.Runnable ), (Ljava/lang/Runnable;)V)(), Thread::sleep, Clock.systemUTC());
    }

    PhoneRegistrationService(PhoneRegistrationRepository repository, RegistrationProviderClient providerClient, PhoneRegistrationPassportGateway passportGateway, RegistrationSecretCipher secretCipher, Executor executor, RegistrationSleeper sleeper, Clock clock) {
        this.repository = repository;
        this.providerClient = providerClient;
        this.passportGateway = passportGateway;
        this.secretCipher = secretCipher;
        this.executor = executor;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public Map<String, Object> testProvider(Map<String, Object> payload) {
        Map<String, Object> source = payload == null ? Map.of() : payload;
        ProviderLogin login = this.providerLogin(source);
        RegistrationProviderClient.ProviderSession session = this.providerClient.authenticate(login.providerType(), login.baseUrl(), login.account(), login.password());
        RegistrationProviderClient.ProviderBalance balance = this.providerClient.balance(login.providerType(), login.baseUrl(), session.token());
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("providerType", login.providerType().name());
        result.put("providerLabel", login.providerType().label());
        result.put("providerBaseUrl", login.baseUrl());
        result.put("providerAccount", this.maskAccount(login.account()));
        result.put("money", balance.money());
        result.put("maxNumbers", balance.maxNumbers());
        result.put("message", login.providerType().label() + " \u63a5\u7801\u5e73\u53f0\u8fde\u63a5\u6210\u529f");
        return result;
    }

    public synchronized Map<String, Object> start(long ownerUserId, Map<String, Object> payload) {
        String projectKey;
        if (ownerUserId <= 0L) {
            throw new IllegalArgumentException("\u7f3a\u5c11\u5f53\u524d\u7cfb\u7edf\u7528\u6237");
        }
        this.repository.findActiveOwned(ownerUserId).ifPresent(this::recoverStaleBatch);
        if (this.repository.findActiveOwned(ownerUserId).isPresent()) {
            throw new IllegalStateException("\u5df2\u6709\u63a5\u7801\u6ce8\u518c\u6279\u6b21\u6b63\u5728\u6267\u884c\uff0c\u8bf7\u5148\u7b49\u5f85\u5176\u7ed3\u675f");
        }
        Map<String, Object> source = payload == null ? Map.of() : payload;
        ProviderLogin login = this.providerLogin(source);
        RegistrationProviderType providerType = login.providerType();
        String string = projectKey = providerType == RegistrationProviderType.D1 ? ProtocolValueSupport.firstText(source, "providerKeyword") : this.requiredText(source, "projectId", "\u9879\u76ee ID");
        if (projectKey.isBlank()) {
            projectKey = PROJECT_NAME;
        }
        if (providerType == RegistrationProviderType.HAOZHU && !projectKey.matches("\\d+")) {
            throw new IllegalArgumentException("\u9879\u76ee ID \u5fc5\u987b\u662f\u5e73\u53f0\u9879\u76ee\u5217\u8868\u4e2d\u7684\u6570\u5b57 sid");
        }
        String realName = this.requiredText(source, "name", "\u5b9e\u540d\u59d3\u540d");
        String idCard = this.requiredText(source, "idCard", "\u8eab\u4efd\u8bc1\u53f7");
        if (!ID_CARD_PATTERN.matcher(idCard).matches()) {
            throw new IllegalArgumentException("\u8eab\u4efd\u8bc1\u53f7\u683c\u5f0f\u4e0d\u6b63\u786e");
        }
        String gamePassword = ProtocolValueSupport.firstText(source, "gamePassword");
        if (gamePassword.isBlank()) {
            gamePassword = this.generatePassword();
        }
        if (gamePassword.length() < 6 || gamePassword.length() > 64) {
            throw new IllegalArgumentException("\u6e38\u620f\u8d26\u53f7\u5bc6\u7801\u957f\u5ea6\u9700\u4e3a 6 \u5230 64 \u4f4d");
        }
        int maxAttempts = (int)ProtocolValueSupport.number(source.get("maxAttempts"));
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (maxAttempts > 10) {
            throw new IllegalArgumentException("\u6700\u591a\u5c1d\u8bd5\u53f7\u7801\u6570\u4e0d\u80fd\u8d85\u8fc7 10");
        }
        Instant now = this.clock.instant();
        PhoneRegistrationBatchEntity batch = new PhoneRegistrationBatchEntity();
        batch.setOwnerUserId(ownerUserId);
        batch.setStatus(PhoneRegistrationStatus.QUEUED.name());
        batch.setStage(PhoneRegistrationStage.QUEUED.name());
        batch.setProviderType(providerType.name());
        batch.setProviderBaseUrl(login.baseUrl());
        batch.setProjectId(projectKey);
        batch.setProviderAccountMasked(this.maskAccount(login.account()));
        batch.setProviderAccountCipher(this.secretCipher.encrypt(login.account()));
        batch.setProviderPasswordCipher(login.password().isBlank() ? null : this.secretCipher.encrypt(login.password()));
        batch.setRealNameCipher(this.secretCipher.encrypt(realName));
        batch.setIdCardCipher(this.secretCipher.encrypt(idCard));
        batch.setGamePasswordCipher(this.secretCipher.encrypt(gamePassword));
        batch.setAttemptCount(0);
        batch.setMaxAttempts(maxAttempts);
        batch.setStatusMessage("\u6279\u6b21\u5df2\u521b\u5efa\uff0c\u7b49\u5f85\u6267\u884c");
        batch.setCreatedAt(this.epoch(now));
        batch.setUpdatedAt(this.epoch(now));
        this.repository.create(batch);
        this.appendLog(batch, PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.QUEUED, "\u6279\u6b21\u5df2\u521b\u5efa\uff1a" + providerType.label() + "\uff0c\u9879\u76ee \u70b9\u70b9\u4e92\u52a8\uff0c\u6700\u591a\u5c1d\u8bd5 " + maxAttempts + " \u4e2a\u53f7\u7801", Map.of("providerAccount", batch.getProviderAccountMasked(), "providerType", providerType.name(), "providerBaseUrl", login.baseUrl(), "projectKey", projectKey));
        long batchId = batch.getId();
        this.executor.execute(() -> this.executeOwnedBatch(batchId, ownerUserId));
        return this.detail(ownerUserId, batchId);
    }

    public List<Map<String, Object>> list(long ownerUserId, int limit) {
        return this.repository.listOwned(ownerUserId, limit).stream().map(this::batchView).toList();
    }

    public Map<String, Object> detail(long ownerUserId, long batchId) {
        PhoneRegistrationBatchEntity batch = this.requireOwned(ownerUserId, batchId);
        Map<String, Object> view = this.batchView(batch);
        view.put("logs", this.repository.listLogs(batchId).stream().map(this::logView).toList());
        return view;
    }

    public Optional<Map<String, Object>> active(long ownerUserId) {
        return this.repository.findActiveOwned(ownerUserId).filter(batch -> !this.recoverStaleBatch((PhoneRegistrationBatchEntity)batch)).map(batch -> this.detail(ownerUserId, batch.getId()));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void executeOwnedBatch(long batchId, long ownerUserId) {
        PhoneRegistrationBatchEntity batch = this.repository.findOwned(batchId, ownerUserId).orElse(null);
        if (batch == null) {
            return;
        }
        RegistrationProviderType providerType = RegistrationProviderType.from(batch.getProviderType());
        String activePhone = null;
        String providerToken = null;
        try {
            String account = this.secretCipher.decrypt(batch.getProviderAccountCipher());
            String password = providerType == RegistrationProviderType.D1 ? "" : this.secretCipher.decrypt(batch.getProviderPasswordCipher());
            String realName = this.secretCipher.decrypt(batch.getRealNameCipher());
            String idCard = this.secretCipher.decrypt(batch.getIdCardCipher());
            String gamePassword = this.secretCipher.decrypt(batch.getGamePasswordCipher());
            this.startRunning(batch);
            this.appendLog(batch, PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.PROVIDER_LOGIN, "\u5f00\u59cb\u6821\u9a8c " + providerType.label() + " \u63a5\u7801\u5e73\u53f0\u51ed\u636e", Map.of("providerType", providerType.name(), "providerAccount", batch.getProviderAccountMasked()));
            this.moveStage(batch, PhoneRegistrationStage.PROVIDER_LOGIN, "\u6b63\u5728\u6821\u9a8c " + providerType.label() + " \u63a5\u7801\u5e73\u53f0\u51ed\u636e");
            RegistrationProviderClient.ProviderSession session = this.providerClient.authenticate(providerType, batch.getProviderBaseUrl(), account, password);
            providerToken = session.token();
            this.appendLog(batch, PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.PROVIDER_LOGIN, providerType.label() + " \u63a5\u7801\u5e73\u53f0\u51ed\u636e\u6709\u6548", session.response());
            try {
                this.moveStage(batch, PhoneRegistrationStage.PROVIDER_BALANCE, "\u6b63\u5728\u67e5\u8be2\u5e73\u53f0\u4f59\u989d");
                RegistrationProviderClient.ProviderBalance balance = this.providerClient.balance(providerType, batch.getProviderBaseUrl(), providerToken);
                String balanceText = "\u5e73\u53f0\u4f59\u989d \u00a5" + this.safe(balance.money());
                if (!providerType.automaticNumberDisposal()) {
                    balanceText = balanceText + "\uff0c\u6700\u5927\u53ef\u540c\u65f6\u53d6\u53f7 " + balance.maxNumbers();
                }
                this.appendLog(batch, PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.PROVIDER_BALANCE, balanceText, Map.of("money", this.safe(balance.money()), "maxNumbers", balance.maxNumbers()));
            }
            catch (RuntimeException exception) {
                this.appendLog(batch, PhoneRegistrationLogLevel.WARNING, PhoneRegistrationStage.PROVIDER_BALANCE, "\u67e5\u8be2\u5e73\u53f0\u4f59\u989d\u5931\u8d25\uff0c\u7ee7\u7eed\u6267\u884c\uff1a" + exception.getMessage(), Map.of());
            }
            boolean terminal = false;
            for (int attempt = 1; attempt <= batch.getMaxAttempts() && !terminal; ++attempt) {
                batch.setAttemptCount(attempt);
                this.moveStage(batch, PhoneRegistrationStage.ACQUIRING_NUMBER, "\u6b63\u5728\u83b7\u53d6\u7b2c " + attempt + " \u4e2a\u53f7\u7801");
                this.appendLog(batch, PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.ACQUIRING_NUMBER, "\u7b2c " + attempt + " \u6b21\u5c1d\u8bd5\uff1a\u8bf7\u6c42\u63a5\u7801\u5e73\u53f0\u5206\u914d\u53f7\u7801", Map.of("attempt", attempt, "maxAttempts", batch.getMaxAttempts()));
                RegistrationProviderClient.ProviderNumber number = this.providerClient.acquireNumber(providerType, batch.getProviderBaseUrl(), providerToken, batch.getProjectId());
                activePhone = number.phone();
                this.updatePhone(batch, number);
                this.appendLog(batch, PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.ACQUIRING_NUMBER, "\u5df2\u83b7\u53d6\u53f7\u7801 " + this.maskPhone(activePhone) + this.carrierText(number) + "\uff0c\u5f00\u59cb\u8bf7\u6c42\u6e38\u620f\u53d1\u9001\u77ed\u4fe1", Map.of("phone", this.maskPhone(activePhone), "providerUid", this.safe(number.uid()), "carrier", this.safe(number.carrier()), "region", this.safe(number.region())));
                this.moveStage(batch, PhoneRegistrationStage.SENDING_GAME_SMS, "\u6b63\u5728\u8bf7\u6c42\u6e38\u620f\u53d1\u9001\u77ed\u4fe1");
                PhoneRegistrationPassportGateway.PassportSendResult sendResult = this.passportGateway.sendCaptcha(activePhone);
                this.appendLog(batch, sendResult.success() ? PhoneRegistrationLogLevel.INFO : PhoneRegistrationLogLevel.WARNING, PhoneRegistrationStage.SENDING_GAME_SMS, (sendResult.success() ? "\u6e38\u620f\u77ed\u4fe1\u5df2\u53d1\u9001\uff1a" : "\u6e38\u620f\u77ed\u4fe1\u53d1\u9001\u5931\u8d25\uff1a") + sendResult.message(), Map.of("status", sendResult.status(), "code", sendResult.code(), "message", sendResult.message()));
                if (!sendResult.success()) {
                    this.releaseCurrent(providerType, batch, providerToken, activePhone, false);
                    activePhone = null;
                    continue;
                }
                this.moveStage(batch, PhoneRegistrationStage.WAITING_SMS, "\u7b49\u5f85\u63a5\u7801\u5e73\u53f0\u63a5\u6536\u77ed\u4fe1");
                String smsCode = this.waitForSms(providerType, batch, providerToken, activePhone);
                if (smsCode.isBlank()) {
                    this.appendLog(batch, PhoneRegistrationLogLevel.WARNING, PhoneRegistrationStage.WAITING_SMS, "\u4e09\u5206\u949f\u5185\u672a\u6536\u5230\u53ef\u7528\u77ed\u4fe1\uff0c\u62c9\u9ed1\u8be5\u53f7\u7801\u5e76\u6362\u53f7", Map.of());
                    this.releaseCurrent(providerType, batch, providerToken, activePhone, true);
                    activePhone = null;
                    continue;
                }
                this.moveStage(batch, PhoneRegistrationStage.VALIDATING_CAPTCHA, "\u6b63\u5728\u6821\u9a8c\u77ed\u4fe1\u9a8c\u8bc1\u7801");
                PhoneRegistrationPassportGateway.PassportCaptchaResult captcha = this.passportGateway.validateCaptcha(activePhone, smsCode);
                this.appendLog(batch, captcha.success() ? PhoneRegistrationLogLevel.INFO : PhoneRegistrationLogLevel.WARNING, PhoneRegistrationStage.VALIDATING_CAPTCHA, (captcha.success() ? "\u9a8c\u8bc1\u7801\u6821\u9a8c\u901a\u8fc7\uff1a" : "\u9a8c\u8bc1\u7801\u6821\u9a8c\u5931\u8d25\uff1a") + captcha.message(), Map.of("status", captcha.status(), "code", captcha.code(), "message", captcha.message()));
                if (!captcha.success()) {
                    this.releaseCurrent(providerType, batch, providerToken, activePhone, true);
                    activePhone = null;
                    continue;
                }
                this.moveStage(batch, PhoneRegistrationStage.REGISTERING_ACCOUNT, "\u6b63\u5728\u521b\u5efa\u624b\u673a\u53f7\u6e38\u620f\u8d26\u53f7");
                PhoneRegistrationPassportGateway.PassportRegistrationResult registration = this.passportGateway.registerWithSms(activePhone, captcha.token(), gamePassword);
                this.appendLog(batch, registration.success() ? PhoneRegistrationLogLevel.INFO : PhoneRegistrationLogLevel.WARNING, PhoneRegistrationStage.REGISTERING_ACCOUNT, (registration.success() ? "\u624b\u673a\u53f7\u8d26\u53f7\u6ce8\u518c\u6210\u529f\uff1a" : "\u624b\u673a\u53f7\u8d26\u53f7\u6ce8\u518c\u5931\u8d25\uff1a") + registration.message(), Map.of("status", registration.status(), "code", registration.code(), "message", registration.message(), "account", this.maskAccount(registration.account())));
                if (!registration.success()) {
                    if (this.isUsedNumberMessage(registration.message())) {
                        this.appendLog(batch, PhoneRegistrationLogLevel.WARNING, PhoneRegistrationStage.REGISTERING_ACCOUNT, "\u6e38\u620f\u8fd4\u56de\u624b\u673a\u53f7\u5df2\u5b58\u5728\uff0c\u5224\u5b9a\u8be5\u53f7\u7801\u5df2\u88ab\u4f7f\u7528\u5e76\u6362\u53f7", Map.of());
                        this.releaseCurrent(providerType, batch, providerToken, activePhone, true);
                        activePhone = null;
                        continue;
                    }
                    this.failBatch(batch, "\u624b\u673a\u53f7\u8d26\u53f7\u6ce8\u518c\u5931\u8d25\uff1a" + registration.message());
                    this.releaseCurrent(providerType, batch, providerToken, activePhone, false);
                    activePhone = null;
                    terminal = true;
                }
                this.moveStage(batch, PhoneRegistrationStage.VERIFYING_REAL_NAME, "\u6b63\u5728\u63d0\u4ea4\u5b9e\u540d\u8ba4\u8bc1");
                PhoneRegistrationPassportGateway.PassportRealNameResult realNameResult = this.passportGateway.verifyRealName(registration.account(), registration.sdkToken(), realName, idCard);
                this.appendLog(batch, realNameResult.outcome() == PhoneRegistrationPassportGateway.RealNameOutcome.FAILED ? PhoneRegistrationLogLevel.ERROR : PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.VERIFYING_REAL_NAME, realNameResult.message(), Map.of("outcome", realNameResult.outcome().name(), "status", realNameResult.status(), "code", realNameResult.code(), "message", realNameResult.message()));
                if (realNameResult.outcome() == PhoneRegistrationPassportGateway.RealNameOutcome.ALREADY_VERIFIED) {
                    this.appendLog(batch, PhoneRegistrationLogLevel.WARNING, PhoneRegistrationStage.VERIFYING_REAL_NAME, "\u8be5\u8d26\u53f7\u65e0\u9700\u5b9e\u540d\uff0c\u5224\u5b9a\u53f7\u7801\u4e3a\u5df2\u4f7f\u7528\u65e7\u53f7\uff0c\u7acb\u5373\u62c9\u9ed1\u5e76\u6362\u53f7", Map.of());
                    this.releaseCurrent(providerType, batch, providerToken, activePhone, true);
                    activePhone = null;
                    continue;
                }
                if (realNameResult.outcome() == PhoneRegistrationPassportGateway.RealNameOutcome.FAILED) {
                    this.failBatch(batch, "\u5b9e\u540d\u8ba4\u8bc1\u5931\u8d25\uff1a" + realNameResult.message());
                    this.releaseCurrent(providerType, batch, providerToken, activePhone, false);
                    activePhone = null;
                    terminal = true;
                    break;
                }
                this.finishSuccess(batch, registration.account(), registration.sdkToken());
                this.releaseCurrent(providerType, batch, providerToken, activePhone, false);
                activePhone = null;
                terminal = true;
            }
            if (terminal) {
                return;
            }
            this.failBatch(batch, "\u5df2\u5c1d\u8bd5 " + batch.getMaxAttempts() + " \u4e2a\u53f7\u7801\uff0c\u4ecd\u672a\u83b7\u5f97\u53ef\u6ce8\u518c\u4e14\u9700\u5b9e\u540d\u7684\u8d26\u53f7");
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.failBatch(batch, "\u6279\u6b21\u7b49\u5f85\u88ab\u4e2d\u65ad");
            this.releaseQuietly(providerType, batch, providerToken, activePhone);
        }
        catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "\u672a\u77e5\u5f02\u5e38" : exception.getMessage();
            this.failBatch(batch, message);
            this.releaseQuietly(providerType, batch, providerToken, activePhone);
        }
        finally {
            this.repository.clearSensitive(batch.getId(), this.clock.instant());
        }
    }

    private String waitForSms(RegistrationProviderType providerType, PhoneRegistrationBatchEntity batch, String token, String phone) throws InterruptedException {
        for (int poll = 1; poll <= 12; ++poll) {
            this.sleeper.sleep(15000L);
            batch.setUpdatedAt(this.epoch(this.clock.instant()));
            this.repository.update(batch);
            RegistrationProviderClient.ProviderSmsResult result = this.providerClient.pollSms(providerType, batch.getProviderBaseUrl(), token, batch.getProjectId(), phone);
            String code = result.verificationCode();
            if (code.isBlank()) {
                code = RegistrationProviderClient.extractVerificationCode(result.sms()).orElse("");
            }
            Object message = result.received() ? (code.isBlank() ? "\u5df2\u6536\u5230\u77ed\u4fe1\u4f46\u672a\u8bc6\u522b\u9a8c\u8bc1\u7801" : "\u5df2\u6536\u5230\u77ed\u4fe1\u9a8c\u8bc1\u7801") : "\u6682\u672a\u6536\u5230\u77ed\u4fe1\uff1a" + this.safe(result.message());
            this.appendLog(batch, PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.WAITING_SMS, "\u7b2c " + poll + "/12 \u6b21\u67e5\u8be2\uff1a" + (String)message, Map.of("poll", poll, "status", result.code(), "message", this.safe(result.message()), "smsLength", result.sms() == null ? 0 : result.sms().length()));
            if (!result.received() || code.isBlank()) continue;
            return code;
        }
        return "";
    }

    private void startRunning(PhoneRegistrationBatchEntity batch) {
        batch.setStatus(PhoneRegistrationStatus.RUNNING.name());
        batch.setStartedAt(this.epoch(this.clock.instant()));
        batch.setUpdatedAt(this.epoch(this.clock.instant()));
        this.repository.update(batch);
    }

    private void moveStage(PhoneRegistrationBatchEntity batch, PhoneRegistrationStage stage, String message) {
        batch.setStatus(PhoneRegistrationStatus.RUNNING.name());
        batch.setStage(stage.name());
        batch.setStatusMessage(message);
        batch.setUpdatedAt(this.epoch(this.clock.instant()));
        this.repository.update(batch);
    }

    private void updatePhone(PhoneRegistrationBatchEntity batch, RegistrationProviderClient.ProviderNumber number) {
        batch.setPhone(number.phone());
        batch.setProviderUid(number.uid());
        batch.setProviderCarrier(number.carrier());
        batch.setProviderRegion(number.region());
        batch.setUpdatedAt(this.epoch(this.clock.instant()));
        this.repository.update(batch);
    }

    private void finishSuccess(PhoneRegistrationBatchEntity batch, String account, String sdkToken) {
        Instant now = this.clock.instant();
        batch.setStatus(PhoneRegistrationStatus.SUCCESS.name());
        batch.setStage(PhoneRegistrationStage.SUCCESS.name());
        batch.setStatusMessage("\u6ce8\u518c\u4e0e\u5b9e\u540d\u6210\u529f\uff0c\u53ef\u4ee5\u8f6c\u5165\u8d77\u53f7\u6a21\u5757");
        batch.setGameAccount(account);
        batch.setEncryptedCredential(AccountCredentialCipher.encode(account, sdkToken));
        batch.setFinishedAt(this.epoch(now));
        batch.setUpdatedAt(this.epoch(now));
        this.repository.update(batch);
        this.appendLog(batch, PhoneRegistrationLogLevel.SUCCESS, PhoneRegistrationStage.SUCCESS, "\u6ce8\u518c\u4e0e\u5b9e\u540d\u5168\u90e8\u5b8c\u6210\uff0c\u8d26\u53f7 " + this.maskAccount(account) + " \u5df2\u53ef\u7528\u4e8e\u8d77\u53f7", Map.of("account", this.maskAccount(account), "needsBootstrap", true));
    }

    private void failBatch(PhoneRegistrationBatchEntity batch, String message) {
        String safeMessage = this.safe(message);
        Instant now = this.clock.instant();
        batch.setStatus(PhoneRegistrationStatus.FAILED.name());
        batch.setStage(PhoneRegistrationStage.FAILED.name());
        batch.setStatusMessage(safeMessage);
        batch.setFailureReason(safeMessage);
        batch.setFinishedAt(this.epoch(now));
        batch.setUpdatedAt(this.epoch(now));
        this.repository.update(batch);
        this.appendLog(batch, PhoneRegistrationLogLevel.ERROR, PhoneRegistrationStage.FAILED, "\u6279\u6b21\u5931\u8d25\uff1a" + safeMessage, Map.of());
    }

    private void releaseCurrent(RegistrationProviderType providerType, PhoneRegistrationBatchEntity batch, String token, String phone, boolean blacklist) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        if (providerType.automaticNumberDisposal()) {
            this.appendLog(batch, PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.ACQUIRING_NUMBER, providerType.label() + (blacklist ? " \u5e73\u53f0\u4f1a\u81ea\u52a8\u62c9\u9ed1\u91cd\u590d\u53f7\u7801\uff0c\u65e0\u9700\u624b\u52a8\u8c03\u7528" : " \u5e73\u53f0\u4f1a\u81ea\u52a8\u91ca\u653e\u53f7\u7801\uff0c\u65e0\u9700\u624b\u52a8\u8c03\u7528"), Map.of("provider", providerType.name(), "automatic", true));
            return;
        }
        try {
            Map<String, Object> response = blacklist ? this.providerClient.blacklist(providerType, batch.getProviderBaseUrl(), token, batch.getProjectId(), phone) : this.providerClient.release(providerType, batch.getProviderBaseUrl(), token, batch.getProjectId(), phone);
            this.appendLog(batch, PhoneRegistrationLogLevel.INFO, PhoneRegistrationStage.ACQUIRING_NUMBER, (blacklist ? "\u5df2\u62c9\u9ed1\u53f7\u7801 " : "\u5df2\u91ca\u653e\u53f7\u7801 ") + this.maskPhone(phone), response);
        }
        catch (RuntimeException exception) {
            this.appendLog(batch, PhoneRegistrationLogLevel.WARNING, PhoneRegistrationStage.ACQUIRING_NUMBER, (blacklist ? "\u62c9\u9ed1\u53f7\u7801\u5931\u8d25\uff1a" : "\u91ca\u653e\u53f7\u7801\u5931\u8d25\uff1a") + exception.getMessage(), Map.of("phone", this.maskPhone(phone)));
        }
    }

    private void releaseQuietly(RegistrationProviderType providerType, PhoneRegistrationBatchEntity batch, String token, String phone) {
        if (phone == null || phone.isBlank() || token == null || token.isBlank()) {
            return;
        }
        if (providerType.automaticNumberDisposal()) {
            return;
        }
        try {
            this.providerClient.release(providerType, batch.getProviderBaseUrl(), token, batch.getProjectId(), phone);
        }
        catch (RuntimeException runtimeException) {
            // empty catch block
        }
    }

    private boolean isUsedNumberMessage(String message) {
        String normalized = this.safe(message).toLowerCase();
        return normalized.contains("\u624b\u673a\u53f7\u5df2\u7ecf\u5b58\u5728") || normalized.contains("\u53f7\u7801\u5df2\u5b58\u5728") || normalized.contains("\u5df2\u6ce8\u518c") || normalized.contains("already exists") || normalized.contains("already registered");
    }

    private void appendLog(PhoneRegistrationBatchEntity batch, PhoneRegistrationLogLevel level, PhoneRegistrationStage stage, String message, Map<String, Object> detail) {
        this.repository.appendLog(batch.getId(), level, stage, message, this.json(detail), this.clock.instant());
    }

    private PhoneRegistrationBatchEntity requireOwned(long ownerUserId, long batchId) {
        PhoneRegistrationBatchEntity batch = this.repository.findOwned(batchId, ownerUserId).orElseThrow(() -> new IllegalArgumentException("\u6279\u6b21\u4e0d\u5b58\u5728\u6216\u65e0\u6743\u8bbf\u95ee"));
        this.recoverStaleBatch(batch);
        return batch;
    }

    private boolean recoverStaleBatch(PhoneRegistrationBatchEntity batch) {
        boolean unfinished;
        boolean bl = unfinished = PhoneRegistrationStatus.RUNNING.name().equals(batch.getStatus()) || PhoneRegistrationStatus.QUEUED.name().equals(batch.getStatus());
        if (!unfinished || batch.getUpdatedAt() == null) {
            return false;
        }
        double now = this.epoch(this.clock.instant());
        if (now - batch.getUpdatedAt() < 120.0) {
            return false;
        }
        this.failBatch(batch, "\u6279\u6b21\u8d85\u8fc7\u4e24\u5206\u949f\u6ca1\u6709\u6267\u884c\u5fc3\u8df3\uff0c\u7591\u4f3c\u670d\u52a1\u91cd\u542f\u6216\u7ebf\u7a0b\u4e2d\u65ad");
        this.repository.clearSensitive(batch.getId(), this.clock.instant());
        return true;
    }

    private Map<String, Object> batchView(PhoneRegistrationBatchEntity batch) {
        RegistrationProviderType providerType = RegistrationProviderType.from(batch.getProviderType());
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", batch.getId());
        result.put("status", batch.getStatus());
        result.put("stage", batch.getStage());
        result.put("stageLabel", this.stageLabel(batch.getStage()));
        result.put("terminal", PhoneRegistrationStatus.valueOf(batch.getStatus()).terminal());
        result.put("providerType", providerType.name());
        result.put("providerLabel", providerType.label());
        result.put("providerBaseUrl", batch.getProviderBaseUrl());
        result.put("providerAccount", batch.getProviderAccountMasked());
        result.put("projectId", providerType == RegistrationProviderType.HAOZHU ? batch.getProjectId() : "");
        result.put("providerKeyword", providerType == RegistrationProviderType.D1 ? batch.getProjectId() : "");
        result.put("projectName", PROJECT_NAME);
        result.put("phone", batch.getPhone());
        result.put("phoneMasked", this.maskPhone(batch.getPhone()));
        result.put("providerUid", batch.getProviderUid());
        result.put("providerCarrier", batch.getProviderCarrier());
        result.put("providerRegion", batch.getProviderRegion());
        result.put("gameAccount", batch.getGameAccount());
        result.put("encryptedCredential", batch.getEncryptedCredential());
        result.put("attemptCount", batch.getAttemptCount());
        result.put("maxAttempts", batch.getMaxAttempts());
        result.put("statusMessage", batch.getStatusMessage());
        result.put("failureReason", batch.getFailureReason());
        result.put("createdAt", batch.getCreatedAt());
        result.put("startedAt", batch.getStartedAt());
        result.put("finishedAt", batch.getFinishedAt());
        result.put("updatedAt", batch.getUpdatedAt());
        result.put("needsBootstrap", PhoneRegistrationStatus.SUCCESS.name().equals(batch.getStatus()) && batch.getEncryptedCredential() != null && !batch.getEncryptedCredential().isBlank());
        return result;
    }

    private Map<String, Object> logView(PhoneRegistrationLogEntity log) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", log.getId());
        result.put("level", log.getLevel());
        result.put("stage", log.getStage());
        result.put("stageLabel", this.stageLabel(log.getStage()));
        result.put("message", log.getMessage());
        result.put("detailJson", log.getDetailJson());
        result.put("createdAt", log.getCreatedAt());
        return result;
    }

    private String stageLabel(String stage) {
        try {
            return PhoneRegistrationStage.valueOf(stage).label();
        }
        catch (IllegalArgumentException exception) {
            return stage == null ? "" : stage;
        }
    }

    private String json(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(detail);
        }
        catch (Exception exception) {
            return null;
        }
    }

    private ProviderLogin providerLogin(Map<String, Object> source) {
        RegistrationProviderType providerType = RegistrationProviderType.from(ProtocolValueSupport.firstText(source, "providerType"));
        if (providerType == RegistrationProviderType.D1) {
            return new ProviderLogin(providerType, "https://api.d1jiema.com", this.requiredText(source, "providerToken", "D1 API Token"), "");
        }
        return new ProviderLogin(providerType, RegistrationProviderClient.normalizeBaseUrl(this.requiredText(source, "providerBaseUrl", "\u670d\u52a1\u5668\u5730\u5740")), this.requiredText(source, "providerAccount", "API \u8d26\u53f7"), this.requiredText(source, "providerPassword", "API \u5bc6\u7801"));
    }

    private String requiredText(Map<String, Object> source, String key, String label) {
        String value = ProtocolValueSupport.firstText(source, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("\u8bf7\u586b\u5199" + label);
        }
        return value;
    }

    private String generatePassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder builder = new StringBuilder("Wjdr@");
        for (int index = 0; index < 12; ++index) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private String maskPhone(String phone) {
        String value = this.safe(phone);
        if (value.length() <= 7) {
            return value.isEmpty() ? "" : "****";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private String maskAccount(String account) {
        String value = this.safe(account);
        if (value.length() <= 8) {
            return value.isEmpty() ? "" : value.substring(0, 2) + "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String carrierText(RegistrationProviderClient.ProviderNumber number) {
        if (this.safe(number.carrier()).isBlank() && this.safe(number.region()).isBlank()) {
            return "";
        }
        return "\uff08" + this.safe(number.carrier()) + (String)(this.safe(number.region()).isBlank() ? "" : " / " + this.safe(number.region())) + "\uff09";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private double epoch(Instant instant) {
        return (double)instant.toEpochMilli() / 1000.0;
    }

    private record ProviderLogin(RegistrationProviderType providerType, String baseUrl, String account, String password) {
    }
}

