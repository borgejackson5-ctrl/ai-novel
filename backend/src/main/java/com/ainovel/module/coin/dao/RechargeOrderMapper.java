package com.ainovel.module.coin.dao;

import com.ainovel.module.coin.domain.entity.RechargeOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RechargeOrderMapper extends BaseMapper<RechargeOrder> {

    @Select("SELECT * FROM t_recharge_order WHERE order_no = #{orderNo} AND is_deleted = 0")
    RechargeOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 原子标记已支付：只有 status=0 才能转 1
     *
     * <p>幂等关键：并发重复回调时，只有一个请求能把状态从 0 改成 1（返回 1），
     * 其余返回 0，从而保证「同一订单只入账一次」
     */
    @Update("UPDATE t_recharge_order SET status = 1, pay_time = NOW() " +
            "WHERE order_no = #{orderNo} AND status = 0 AND is_deleted = 0")
    int markPaid(@Param("orderNo") String orderNo);

    /**
     * 原子标记已取消：只有 status=0 才能转 2
     *
     * <p>与 {@link #markPaid} 条件互斥（都要求 status=0），保证「已支付」与「已取消」
     * 不会互相覆盖，超时关单不会误关已支付订单。
     */
    @Update("UPDATE t_recharge_order SET status = 2 " +
            "WHERE order_no = #{orderNo} AND status = 0 AND is_deleted = 0")
    int markCanceled(@Param("orderNo") String orderNo);

    /**
     * 查询用户最近一条待支付订单（复用存量单：一个用户同一时刻最多一笔待支付）
     */
    @Select("SELECT * FROM t_recharge_order WHERE user_id = #{userId} AND status = 0 AND is_deleted = 0 " +
            "ORDER BY id DESC LIMIT 1")
    RechargeOrder selectLatestPending(@Param("userId") Long userId);
}
