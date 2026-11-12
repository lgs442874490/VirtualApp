/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.stereotype.Repository
 */
package com.wjdr.tool.modules.registration.persistence;

import com.wjdr.tool.modules.registration.model.PhoneRegistrationLogLevel;
import com.wjdr.tool.modules.registration.model.PhoneRegistrationStage;
import com.wjdr.tool.modules.registration.persistence.entity.PhoneRegistrationBatchEntity;
import com.wjdr.tool.modules.registration.persistence.entity.PhoneRegistrationLogEntity;
import com.wjdr.tool.modules.registration.persistence.mapper.PhoneRegistrationBatchMapper;
import com.wjdr.tool.modules.registration.persistence.mapper.PhoneRegistrationLogMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class PhoneRegistrationRepository {
    public static final int MAX_LOG_ROWS = 500;
    private static final int MAX_LOG_MESSAGE_LENGTH = 1000;
    private final PhoneRegistrationBatchMapper batchMapper;
    private final PhoneRegistrationLogMapper logMapper;

    public PhoneRegistrationRepository(PhoneRegistrationBatchMapper batchMapper, PhoneRegistrationLogMapper logMapper) {
        this.batchMapper = batchMapper;
        this.logMapper = logMapper;
    }

    public PhoneRegistrationBatchEntity create(PhoneRegistrationBatchEntity entity) {
        this.batchMapper.insert(entity);
        return entity;
    }

    public Optional<PhoneRegistrationBatchEntity> findOwned(long batchId, long ownerUserId) {
        PhoneRegistrationBatchEntity entity = (PhoneRegistrationBatchEntity)this.batchMapper.selectById(Long.valueOf(batchId));
        return entity != null && entity.getOwnerUserId() != null && entity.getOwnerUserId() == ownerUserId ? Optional.of(entity) : Optional.empty();
    }

    public Optional<PhoneRegistrationBatchEntity> findActiveOwned(long ownerUserId) {
        return Optional.ofNullable(this.batchMapper.findActiveOwned(ownerUserId));
    }

    public List<PhoneRegistrationBatchEntity> listOwned(long ownerUserId, int limit) {
        return this.batchMapper.listOwned(ownerUserId, Math.clamp((long)limit, (int)1, (int)50));
    }

    public void update(PhoneRegistrationBatchEntity entity) {
        this.batchMapper.updateById(entity);
    }

    public void appendLog(long batchId, PhoneRegistrationLogLevel level, PhoneRegistrationStage stage, String message, String detailJson, Instant createdAt) {
        PhoneRegistrationLogEntity entity = new PhoneRegistrationLogEntity();
        entity.setBatchId(batchId);
        entity.setLevel(level.name());
        entity.setStage(stage.name());
        entity.setMessage(this.limit(message == null ? "" : message));
        entity.setDetailJson(detailJson);
        entity.setCreatedAt(this.epoch(createdAt));
        this.logMapper.insert(entity);
    }

    public List<PhoneRegistrationLogEntity> listLogs(long batchId) {
        return this.logMapper.listByBatch(batchId, 500);
    }

    public void clearSensitive(long batchId, Instant now) {
        this.batchMapper.clearSensitive(batchId, this.epoch(now));
    }

    private String limit(String value) {
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private double epoch(Instant value) {
        return (double)value.toEpochMilli() / 1000.0;
    }
}

