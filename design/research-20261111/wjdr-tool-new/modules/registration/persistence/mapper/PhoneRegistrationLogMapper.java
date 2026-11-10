/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.baomidou.mybatisplus.core.mapper.BaseMapper
 *  org.apache.ibatis.annotations.Mapper
 *  org.apache.ibatis.annotations.Param
 *  org.apache.ibatis.annotations.Select
 */
package com.wjdr.tool.modules.registration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wjdr.tool.modules.registration.persistence.entity.PhoneRegistrationLogEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PhoneRegistrationLogMapper
extends BaseMapper<PhoneRegistrationLogEntity> {
    @Select(value={"SELECT * FROM registration_phone_batch_log\nWHERE batch_id = #{batchId}\nORDER BY id\nLIMIT #{limit}\n"})
    public List<PhoneRegistrationLogEntity> listByBatch(@Param(value="batchId") long var1, @Param(value="limit") int var3);
}

