package com.ainovel.module.user.dao;

import com.ainovel.module.user.domain.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 原子扣减虚拟币（防超扣）
     */
    @Update("UPDATE t_user SET coin_balance = coin_balance - #{amount} " +
            "WHERE id = #{id} AND coin_balance >= #{amount} AND is_deleted = 0")
    int deductCoin(@Param("id") Long id, @Param("amount") int amount);

    /**
     * 原子增加虚拟币
     */
    @Update("UPDATE t_user SET coin_balance = coin_balance + #{amount} WHERE id = #{id} AND is_deleted = 0")
    int addCoin(@Param("id") Long id, @Param("amount") int amount);
}

