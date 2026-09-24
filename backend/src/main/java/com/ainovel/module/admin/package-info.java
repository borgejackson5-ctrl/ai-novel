/**
 * 管理端：审核、用户/订单管理、看板与操作日志。
 *
 * <p>声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}：仅约束模块之间单向依赖、不成环，
 * 不强制「外部只能访问模块的根包」；本项目的模块内部包本就对外可见，不做 API / internal 的区分。
 *
 * <p>此处显式列全了允许依赖的模块。管理端本身承担跨表聚合职责：作品列表需带分类名、
 * 订单列表需带用户昵称、数据看板需对多张表计数。若将这些查询收回各业务模块，相当于为每个
 * 模块增加一批管理端专用方法，耦合只是转移到其他位置。因此此处显式声明这些依赖，并要求全部登记：
 * 一旦向 admin 引入指向新模块的依赖，测试会立即报出，由人工判断是否确有必要。
 */
@ApplicationModule(
        type = ApplicationModule.Type.OPEN,
        allowedDependencies = {
                "module.ai",
                "module.category",
                "module.coin",
                "module.feedback",
                "module.message",
                "module.monitor",
                "module.novel",
                "module.search",
                "module.subscribe",
                "module.user"
        })
package com.ainovel.module.admin;

import org.springframework.modulith.ApplicationModule;
