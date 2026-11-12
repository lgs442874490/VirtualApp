/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.baomidou.mybatisplus.core.mapper.BaseMapper
 *  org.apache.ibatis.annotations.Mapper
 *  org.apache.ibatis.annotations.Param
 *  org.apache.ibatis.annotations.Select
 *  org.apache.ibatis.annotations.Update
 */
package com.wjdr.tool.modules.registration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wjdr.tool.modules.registration.persistence.entity.PhoneRegistrationBatchEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PhoneRegistrationBatchMapper
extends BaseMapper<PhoneRegistrationBatchEntity> {
    @Select(value={"SELECT * FROM registration_phone_batch\nWHERE owner_user_id = #{ownerUserId}\n  AND status IN ('QUEUED', 'RUNNING')\nORDER BY id DESC\nLIMIT 1\n"})
    public PhoneRegistrationBatchEntity findActiveOwned(@Param(value="ownerUserId") long var1);

    @Select(value={"SELECT * FROM registration_phone_batch\nWHERE owner_user_id = #{ownerUserId}\nORDER BY id DESC\nLIMIT #{limit}\n"})
    public List<PhoneRegistrationBatchEntity> listOwned(@Param(value="ownerUserId") long var1, @Param(value="limit") int var3);

    @Update(value={"UPDATE registration_phone_batch\nSET provider_account_cipher = NULL,\n    provider_password_cipher = NULL,\n    real_name_cipher = NULL,\n    id_card_cipher = NULL,\n    game_password_cipher = NULL,\n    updated_at = #{now}\nWHERE id = #{batchId}\n"})
    public void clearSensitive(@Param(value="batchId") long var1, @Param(value="now") double var3);
}

