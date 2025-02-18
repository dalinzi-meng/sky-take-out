package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.service.WorkspaceService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.*;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private WebSocketServer webSocketServer;
    @Autowired
    private WorkspaceService workspaceService;

    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        // 如果地址簿为空，则不能提交订单
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            // 抛出业务异常
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        // 如果购物车的数据为空，则不能提交订单
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(BaseContext.getCurrentId());
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);
        if (list == null || list.isEmpty()) {
            // 抛出业务异常
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        // 向订单表插入一条数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setAddress(addressBook.getDetail());
        orders.setUserId(BaseContext.getCurrentId());

        orderMapper.insert(orders);

        // 向订单明细表插入多条数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : list) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        // 清空购物车数据
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());

        return OrderSubmitVO.builder()
                .id(orders.getId())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .orderTime(orders.getOrderTime())
                .build();

    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);
        //调用微信支付接口，生成预支付交易单
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), //商户订单号
                new BigDecimal(0.01), //支付金额，单位 元
                "苍穹外卖订单", //商品描述
                user.getOpenid() //微信用户的openid
        );
        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }
        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));
        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        // 根据订单号查询当前用户的订单
        Orders ordersDB = orderMapper.getByNumberAndUserId(outTradeNo, userId);
        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();
        orderMapper.update(orders);

        Map map = new HashMap();
        map.put("type", 1);
        map.put("orderId", ordersDB.getId());
        map.put("content", "订单号：" + ordersDB.getNumber());

        String json = JSONObject.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }

    /**
     * 历史订单分页查询
     *
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    @Override
    public PageResult getHistoryOrders(Integer pageNum, Integer pageSize, Integer status) {
        PageHelper.startPage(pageNum, pageSize);
        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList<>();
        if (page != null && page.getTotal() > 0) {
            for (Orders orders : page) {
                Long orderId = orders.getId();// 订单id

                // 查询订单明细
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orderId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);

                list.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(), list);
    }

    /**
     * 根据id查询订单详情
     *
     * @param id
     * @return
     */
    @Override
    public OrderVO getOrderDetail(Long id) {
        // 根据id查询订单
        Orders orders = orderMapper.getById(id);
        // 根据订单id查询订单明细
        List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orders.getId());
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetails);
        return orderVO;
    }

    @Override
    public void cancel(Long id) throws Exception {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);
        // 判断订单是否存在
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 判断订单状态，如果是待付款之后的状态则不能取消订单
        if (ordersDB.getStatus() > 2) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());

        // 订单处于待接单状态下取消，需要进行退款
        /*if (ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            weChatPayUtil.refund(ordersDB.getNumber(), ordersDB.getNumber(), BigDecimal.valueOf(0.01), BigDecimal.valueOf(0.01));
            // 退款成功后,修改订单状态
            orders.setPayStatus(Orders.REFUND);
        }*/

        // 修改订单状态，订单状态改为取消状态
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 再来一单
     *
     * @param id
     */
    @Override
    public void repetition(Long id) {
        // 查询当前用户id
        Long userId = BaseContext.getCurrentId();

        // 根据订单id查询当前订单详情
        List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(id);

        // 将订单详情对象转换为购物车对象
        List<ShoppingCart> shoppingCarts = orderDetails.stream().map(orderDetail -> {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
        }).collect(Collectors.toList());

        // 将购物车对象批量添加到数据库
        shoppingCartMapper.insertBatch(shoppingCarts);
    }

    @Override
    public PageResult pageQuery(OrdersPageQueryDTO ordersPageQueryDTO) {
        // 使用分页插件
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        // 部分订单状态，需要额外返回订单菜品信息，将Orders转化为OrderVO
        List<OrderVO> orderVOList = getOrderVOList(page);
        return new PageResult(page.getTotal(), orderVOList);
    }

    /**
     * 订单统计
     *
     * @return
     */
    @Override
    public OrderStatisticsVO statistics() {
        // 根据状态，分别查询出待接单、待派送、派送中的订单数量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 将查询出的数据封装到orderStatisticsVO中响应
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 接单
     *
     * @param ordersConfirmDTO
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders = Orders.builder()
                .id(ordersConfirmDTO.getId())
                .status(Orders.CONFIRMED)
                .build();
        orderMapper.update(orders);
    }

    /**
     * 拒单
     *
     * @param ordersRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        // 查询订单
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());
        // 判断订单是否存在并且订单的状态为待接单,否则抛出异常
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 查看订单是否付款了,如果付款则申请退款
        /*if (ordersDB.getPayStatus().equals(Orders.PAID)) {
            // 如果是已付款，则调用微信的退款接口
            String refund = weChatPayUtil.refund(ordersDB.getNumber(), ordersDB.getNumber(), new BigDecimal(0.01), new BigDecimal(0.01));
            log.info("申请退款成功，结果：{}", refund);
        }*/
        // 更改订单状态
        Orders orders = new Orders();
        orders.setId(ordersRejectionDTO.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 管理员取消订单
     *
     * @param ordersRejectionDTO
     */
    @Override
    public void AdminCancel(OrdersRejectionDTO ordersRejectionDTO) {
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        /*if(ordersDB.getPayStatus().equals(Orders.PAID)){
            String refund = weChatPayUtil.refund(ordersDB.getNumber(), ordersDB.getNumber(), new BigDecimal(0.01), new BigDecimal(0.01));
            log.info("申请退款成功，结果：{}", refund);
        }*/
        Orders orders = new Orders();
        orders.setId(ordersRejectionDTO.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    // 派送订单
    @Override
    public void delivery(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders = new Orders();
        orders.setId(id);
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orderMapper.update(orders);
    }

    // 完成订单
    @Override
    public void complete(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在，并且状态为4
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        // 更新订单状态,状态转为完成
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    // 催单
    @Override
    public void reminder(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB != null) {
            Map map = new HashMap();
            map.put("type", 2);
            map.put("orderId", ordersDB.getId());
            map.put("content", "订单号：" + ordersDB.getNumber());

            webSocketServer.sendToAllClient(JSONObject.toJSONString(map));
        } else {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
    }

    /**
     * 营业额统计
     *
     * @param begin
     * @param end
     * @return
     */
    @Override
    public TurnoverReportVO turnoverStatistics(LocalDate begin, LocalDate end) {
        /*// 新建dateList集合
        List<LocalDate> dateList = new ArrayList<>();
        // 循环添加到dateList中
        while (!begin.isAfter(end)) {
            dateList.add(begin);
            begin = begin.plusDays(1);
        }
        // 创建一个turnoverList集合
        List<Double> turnoverList = new ArrayList<>();
        // 循环遍历dateList集合
        for (LocalDate date : dateList) {
            // 为date添加时分秒
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);
            // 将参数都添加到map中
            Map map = new HashMap();
            map.put("endTime", endTime);
            map.put("beginTime", beginTime);
            map.put("status", Orders.COMPLETED);
            Double turnover = orderMapper.sumByMap(map);
            turnoverList.add(turnover == null ? 0.0 : turnover);
        }
        return TurnoverReportVO.builder().dateList(StringUtils.join(dateList, ",")).turnoverList(StringUtils.join(turnoverList, ",")).build();*/

        // 新建dateList集合
        List<LocalDate> dateList = new ArrayList<>();
        // 循环添加到dateList中
        while (!begin.isAfter(end)) {
            dateList.add(begin);
            begin = begin.plusDays(1);
        }
        Map map = new HashMap();
        map.put("endTime", LocalDateTime.of(dateList.get(dateList.size() - 1), LocalTime.MAX));
        map.put("beginTime", LocalDateTime.of(dateList.get(0), LocalTime.MIN));
        map.put("status", Orders.COMPLETED);
        // 查询指定日期范围内的所有订单数据
        List<Orders> ordersList = orderMapper.getOrdersByDateRange(map);

        // 使用 Map 按日期分组
        Map<LocalDate, List<Orders>> ordersByDateMap = ordersList.stream()
                .collect(Collectors.groupingBy(order -> order.getOrderTime().toLocalDate()));

        // 创建一个turnoverList集合
        List<Double> turnoverList = new ArrayList<>();

        // 计算每日营业额
        for (LocalDate date : dateList) {
            List<Orders> ordersForDate = ordersByDateMap.getOrDefault(date, new ArrayList<>());
            Double totalTurnover = ordersForDate.stream()
                    .mapToDouble(order -> order.getAmount().doubleValue())
                    .sum();
            turnoverList.add(totalTurnover);
        }

        // 构建 TurnoverReportVO
        return TurnoverReportVO.builder()
                .dateList(String.join(",", dateList.stream().map(LocalDate::toString).collect(Collectors.toList())))
                .turnoverList(String.join(",", turnoverList.stream().map(String::valueOf).collect(Collectors.toList())))
                .build();
    }

    /**
     * 用户统计
     *
     * @param begin
     * @param end
     * @return
     */
    @Override
    public UserReportVO userStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> list = new ArrayList<>();
        while (!begin.isAfter(end)) {
            list.add(begin);
            begin = begin.plusDays(1);
        }
        // 创建一个map集合,用于查询该时间段内的所有用户
        Map map = new HashMap();
        map.put("endTime", LocalDateTime.of(list.get(list.size() - 1), LocalTime.MAX));
        map.put("beginTime", LocalDateTime.of(list.get(0), LocalTime.MIN));
        List<User> userList = userMapper.getByMap(map);
        List<Integer> totalUserList = new ArrayList<>();
        List<Integer> newUserList = new ArrayList<>();
        for (LocalDate date : list) {
            // 获取该日期下新增用户的数量
            Integer newUsers = userList.stream()
                    .filter(user -> user.getCreateTime().toLocalDate().equals(date))
                    .map(User::getId)
                    .toList()
                    .size();
            // 获取该日期下总用户的数量
            Integer totalUsers = userList.stream()
                    .filter(user -> user.getCreateTime().toLocalDate().isBefore(date))
                    .map(User::getId).toList().size();
            totalUserList.add(totalUsers);
            newUserList.add(newUsers);
        }
        return UserReportVO.builder().dateList(StringUtils.join(list, ",")).newUserList(StringUtils.join(newUserList, ",")).totalUserList(StringUtils.join(totalUserList, ",")).build();
    }

    /**
     * 订单统计
     *
     * @param begin
     * @param end
     * @return
     */
    @Override
    public OrderReportVO ordersStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = new ArrayList<>();
        // 循环添加到dateList中
        while (!begin.isAfter(end)) {
            dateList.add(begin);
            begin = begin.plusDays(1);
        }
        Map map = new HashMap();
        map.put("endTime", LocalDateTime.of(dateList.get(dateList.size() - 1), LocalTime.MAX));
        map.put("beginTime", LocalDateTime.of(dateList.get(0), LocalTime.MIN));
        // 查询指定日期范围内的所有订单数据
        List<Orders> ordersList = orderMapper.getOrdersByDateRange(map);
        // 统计每日的订单数量,不考虑订单状态
        List<Long> orderCountList = dateList.stream().map(date -> {
            return ordersList.stream()
                    .filter(order -> order.getOrderTime().toLocalDate().equals(date))
                    .count();
        }).toList();
        // 统计每日的订单数量,只考虑已完成的订单
        List<Long> validOrderCountList = dateList.stream().map(date -> {
            return ordersList.stream()
                    .filter(order -> order.getOrderTime().toLocalDate().equals(date) && order.getStatus().equals(Orders.COMPLETED))
                    .count();
        }).toList();
        // 统计该时间段内的订单总数,不考虑订单状态
        int totalOrderCount = ordersList.size();
        // 统计该时间段内的订单总数,只考虑已完成的订单
        int validOrderCount = ordersList.stream()
                .filter(order -> order.getStatus().equals(Orders.COMPLETED))
                .toList().size();
        Double orderCompletionRate = 0.0;
        // 订单完成率
        if (totalOrderCount != 0) {
            orderCompletionRate = (double) validOrderCount / (double) totalOrderCount;
        }
        return OrderReportVO.builder().totalOrderCount(totalOrderCount).validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate).dateList(StringUtils.join(dateList, ","))
                .orderCountList(StringUtils.join(orderCountList, ",")).validOrderCountList(StringUtils.join(validOrderCountList, ",")).build();
    }

    /**
     * 销量排名
     *
     * @param begin
     * @param end
     * @return
     */
    @Override
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        Map map = new HashMap();
        map.put("endTime", LocalDateTime.of(end, LocalTime.MAX));
        map.put("beginTime", LocalDateTime.of(begin, LocalTime.MIN));
        map.put("status", Orders.COMPLETED);
        List<GoodsSalesDTO> salesTop10 = orderMapper.getSalesTop10(map);
        List<String> names = salesTop10.stream().map(GoodsSalesDTO::getName).toList();
        List<Integer> numbers = salesTop10.stream().map(GoodsSalesDTO::getNumber).toList();
        return SalesTop10ReportVO.builder().nameList(StringUtils.join(names, ",")).numberList(StringUtils.join(numbers, ",")).build();
    }

    @Override
    public void export(HttpServletResponse response) {
        // 获取30天前的日期
        LocalDateTime begin = LocalDateTime.of(LocalDate.now().minusDays(30), LocalTime.MIN);
        // 获取昨天的日期
        LocalDateTime end = LocalDateTime.of(LocalDate.now().minusDays(1), LocalTime.MAX);
        // 获取数据
        BusinessDataVO businessData = workspaceService.getBusinessData(begin, end);
        // 获取xlsx模板文件
        InputStream resourceAsStream = this.getClass().getClassLoader().getResourceAsStream("template/运营数据报表模板.xlsx");
        // 创建xlsx文件
        try {
            XSSFWorkbook excel = new XSSFWorkbook(resourceAsStream);
            XSSFSheet sheetAt = excel.getSheetAt(0);
            sheetAt.getRow(1).getCell(1).setCellValue("时间:"+begin.toLocalDate()+"~"+end.toLocalDate());

            XSSFRow row = sheetAt.getRow(3);
            row.getCell(2).setCellValue(businessData.getTurnover());
            row.getCell(4).setCellValue(businessData.getOrderCompletionRate());
            row.getCell(6).setCellValue(businessData.getNewUsers());

            row = sheetAt.getRow(4);
            row.getCell(2).setCellValue(businessData.getValidOrderCount());
            row.getCell(4).setCellValue(businessData.getUnitPrice());

            // 填充明细数据
            for (int i = 0; i < 30; i++) {
                LocalDate date = begin.toLocalDate().plusDays(i);
                BusinessDataVO businessData1 = workspaceService.getBusinessData(LocalDateTime.of(date, LocalTime.MIN), LocalDateTime.of(date, LocalTime.MAX));
                row = sheetAt.getRow(7 + i);
                row.getCell(1).setCellValue(date.toString());
                row.getCell(2).setCellValue(businessData1.getTurnover());
                row.getCell(3).setCellValue(businessData1.getValidOrderCount());
                row.getCell(4).setCellValue(businessData1.getOrderCompletionRate());
                row.getCell(5).setCellValue(businessData1.getUnitPrice());
                row.getCell(6).setCellValue(businessData1.getNewUsers());
            }

            ServletOutputStream outputStream = response.getOutputStream();
            excel.write(outputStream);

            outputStream.close();
            excel.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // 查询订单详情
    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        List<OrderVO> orderVOList = new ArrayList<>();
        // 创建一个orderVO对象
        List<Orders> result = page.getResult();
        if (!CollectionUtils.isEmpty(result)) {
            for (Orders orders : result) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                String orderDishes = getOrderDishesStr(orders);
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }


    // 拼接订单菜品信息
    private String getOrderDishesStr(Orders orders) {
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        List<String> collectList = orderDetailList.stream().map(orderDetail -> {
            return orderDetail.getName() + "*" + orderDetail.getNumber() + ";";
        }).collect(Collectors.toList());
        return String.join("", collectList);
    }
}
