package com.sky.task;

import com.alibaba.fastjson.JSONObject;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.websocket.WebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Component
public class WebSocketTask {
    @Autowired
    private WebSocketServer webSocketServer;
    @Autowired
    private OrderMapper orderMapper;

    /**
     * 通过WebSocket每隔5秒向客户端发送消息
     */
//    @Scheduled(cron = "0 * * * * ?")
    public void sendMessageToClient() {
        Orders ordersDB = orderMapper.getById(14L);
        Map map = new HashMap();
        map.put("type",1);
        map.put("orderId",ordersDB.getId());
        map.put("content","订单号："+ordersDB.getNumber());

        String json = JSONObject.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }
}
