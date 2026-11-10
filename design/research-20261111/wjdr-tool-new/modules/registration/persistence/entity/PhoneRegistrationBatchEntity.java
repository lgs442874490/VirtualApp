/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.baomidou.mybatisplus.annotation.IdType
 *  com.baomidou.mybatisplus.annotation.TableId
 *  com.baomidou.mybatisplus.annotation.TableName
 *  lombok.Generated
 */
package com.wjdr.tool.modules.registration.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Generated;

@TableName(value="registration_phone_batch")
public class PhoneRegistrationBatchEntity {
    @TableId(value="id", type=IdType.AUTO)
    private Long id;
    private Long ownerUserId;
    private String status;
    private String stage;
    private String providerType;
    private String providerBaseUrl;
    private String projectId;
    private String providerAccountMasked;
    private String providerAccountCipher;
    private String providerPasswordCipher;
    private String realNameCipher;
    private String idCardCipher;
    private String gamePasswordCipher;
    private String phone;
    private String providerUid;
    private String providerCarrier;
    private String providerRegion;
    private String gameAccount;
    private String encryptedCredential;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String statusMessage;
    private String failureReason;
    private Double createdAt;
    private Double startedAt;
    private Double finishedAt;
    private Double updatedAt;

    @Generated
    public Long getId() {
        return this.id;
    }

    @Generated
    public Long getOwnerUserId() {
        return this.ownerUserId;
    }

    @Generated
    public String getStatus() {
        return this.status;
    }

    @Generated
    public String getStage() {
        return this.stage;
    }

    @Generated
    public String getProviderType() {
        return this.providerType;
    }

    @Generated
    public String getProviderBaseUrl() {
        return this.providerBaseUrl;
    }

    @Generated
    public String getProjectId() {
        return this.projectId;
    }

    @Generated
    public String getProviderAccountMasked() {
        return this.providerAccountMasked;
    }

    @Generated
    public String getProviderAccountCipher() {
        return this.providerAccountCipher;
    }

    @Generated
    public String getProviderPasswordCipher() {
        return this.providerPasswordCipher;
    }

    @Generated
    public String getRealNameCipher() {
        return this.realNameCipher;
    }

    @Generated
    public String getIdCardCipher() {
        return this.idCardCipher;
    }

    @Generated
    public String getGamePasswordCipher() {
        return this.gamePasswordCipher;
    }

    @Generated
    public String getPhone() {
        return this.phone;
    }

    @Generated
    public String getProviderUid() {
        return this.providerUid;
    }

    @Generated
    public String getProviderCarrier() {
        return this.providerCarrier;
    }

    @Generated
    public String getProviderRegion() {
        return this.providerRegion;
    }

    @Generated
    public String getGameAccount() {
        return this.gameAccount;
    }

    @Generated
    public String getEncryptedCredential() {
        return this.encryptedCredential;
    }

    @Generated
    public Integer getAttemptCount() {
        return this.attemptCount;
    }

    @Generated
    public Integer getMaxAttempts() {
        return this.maxAttempts;
    }

    @Generated
    public String getStatusMessage() {
        return this.statusMessage;
    }

    @Generated
    public String getFailureReason() {
        return this.failureReason;
    }

    @Generated
    public Double getCreatedAt() {
        return this.createdAt;
    }

    @Generated
    public Double getStartedAt() {
        return this.startedAt;
    }

    @Generated
    public Double getFinishedAt() {
        return this.finishedAt;
    }

    @Generated
    public Double getUpdatedAt() {
        return this.updatedAt;
    }

    @Generated
    public void setId(Long id) {
        this.id = id;
    }

    @Generated
    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    @Generated
    public void setStatus(String status) {
        this.status = status;
    }

    @Generated
    public void setStage(String stage) {
        this.stage = stage;
    }

    @Generated
    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    @Generated
    public void setProviderBaseUrl(String providerBaseUrl) {
        this.providerBaseUrl = providerBaseUrl;
    }

    @Generated
    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    @Generated
    public void setProviderAccountMasked(String providerAccountMasked) {
        this.providerAccountMasked = providerAccountMasked;
    }

    @Generated
    public void setProviderAccountCipher(String providerAccountCipher) {
        this.providerAccountCipher = providerAccountCipher;
    }

    @Generated
    public void setProviderPasswordCipher(String providerPasswordCipher) {
        this.providerPasswordCipher = providerPasswordCipher;
    }

    @Generated
    public void setRealNameCipher(String realNameCipher) {
        this.realNameCipher = realNameCipher;
    }

    @Generated
    public void setIdCardCipher(String idCardCipher) {
        this.idCardCipher = idCardCipher;
    }

    @Generated
    public void setGamePasswordCipher(String gamePasswordCipher) {
        this.gamePasswordCipher = gamePasswordCipher;
    }

    @Generated
    public void setPhone(String phone) {
        this.phone = phone;
    }

    @Generated
    public void setProviderUid(String providerUid) {
        this.providerUid = providerUid;
    }

    @Generated
    public void setProviderCarrier(String providerCarrier) {
        this.providerCarrier = providerCarrier;
    }

    @Generated
    public void setProviderRegion(String providerRegion) {
        this.providerRegion = providerRegion;
    }

    @Generated
    public void setGameAccount(String gameAccount) {
        this.gameAccount = gameAccount;
    }

    @Generated
    public void setEncryptedCredential(String encryptedCredential) {
        this.encryptedCredential = encryptedCredential;
    }

    @Generated
    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    @Generated
    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    @Generated
    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    @Generated
    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    @Generated
    public void setCreatedAt(Double createdAt) {
        this.createdAt = createdAt;
    }

    @Generated
    public void setStartedAt(Double startedAt) {
        this.startedAt = startedAt;
    }

    @Generated
    public void setFinishedAt(Double finishedAt) {
        this.finishedAt = finishedAt;
    }

    @Generated
    public void setUpdatedAt(Double updatedAt) {
        this.updatedAt = updatedAt;
    }
}

