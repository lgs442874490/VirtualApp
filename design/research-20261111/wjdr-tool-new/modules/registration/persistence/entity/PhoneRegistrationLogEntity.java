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

@TableName(value="registration_phone_batch_log")
public class PhoneRegistrationLogEntity {
    @TableId(value="id", type=IdType.AUTO)
    private Long id;
    private Long batchId;
    private String level;
    private String stage;
    private String message;
    private String detailJson;
    private Double createdAt;

    @Generated
    public Long getId() {
        return this.id;
    }

    @Generated
    public Long getBatchId() {
        return this.batchId;
    }

    @Generated
    public String getLevel() {
        return this.level;
    }

    @Generated
    public String getStage() {
        return this.stage;
    }

    @Generated
    public String getMessage() {
        return this.message;
    }

    @Generated
    public String getDetailJson() {
        return this.detailJson;
    }

    @Generated
    public Double getCreatedAt() {
        return this.createdAt;
    }

    @Generated
    public void setId(Long id) {
        this.id = id;
    }

    @Generated
    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    @Generated
    public void setLevel(String level) {
        this.level = level;
    }

    @Generated
    public void setStage(String stage) {
        this.stage = stage;
    }

    @Generated
    public void setMessage(String message) {
        this.message = message;
    }

    @Generated
    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }

    @Generated
    public void setCreatedAt(Double createdAt) {
        this.createdAt = createdAt;
    }
}

