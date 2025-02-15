package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class OrderTask {
    @Autowired
    private OrderMapper orderMapper;

    /**
     * 处理超时订单,下单时间超过15分钟未支付，自动取消订单
     */
    @Scheduled(cron = "0 * * * * ?")
    public void processTimeoutOrder() {
        log.info("定时处理超时订单");
        // 计算当前时间之前15分钟的时间
        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);
        List<Orders> ordersDB = orderMapper.processTimeoutOrder(Orders.PENDING_PAYMENT, time);
        if (ordersDB != null && !ordersDB.isEmpty()) {
            List<Long> ids = ordersDB.stream().map(Orders::getId).toList();
            Map<String, Object> params = new HashMap<>();
            params.put("status", Orders.CANCELLED); // 假设状态为2表示取消
            params.put("cancelReason", "订单超时,自动取消");
            params.put("cancelTime", LocalDateTime.now());
            params.put("ids", ids);
            orderMapper.updateOrderStatus(params);
        }


    }

    /**
     * 处理处于派送中的订单,超过60分钟未送达，自动完成订单
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void processDeliveryOrder() {
        log.info("定时处理处于派送中的订单");
        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);
        List<Orders> ordersDB = orderMapper.processTimeoutOrder(Orders.DELIVERY_IN_PROGRESS, time);
        if (ordersDB != null && !ordersDB.isEmpty()) {
            List<Long> ids = ordersDB.stream().map(Orders::getId).toList();
            Map<String, Object> params = new HashMap<>();
            params.put("status", Orders.COMPLETED);
            params.put("ids", ids);
            orderMapper.updateOrderStatus(params);
        }
    }
}
