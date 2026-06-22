import java.util.List;

/**
 * 订单控制器 —— 演示用，故意包含多种缺陷，供 AI 初审 demo 展示。
 * 注意：本文件所有问题都是有意植入的，请勿在生产代码中参考。
 */
public class OrderController {

    /**
     * 创建订单 —— 入参未校验 + 空指针风险
     */
    public String createOrder(String userId, String productId, int count) {
        // 入参未校验：userId / productId 为空、count<=0 都没检查
        // 直接使用 productId.length() —— 当 productId 为 null 时空指针
        if (productId.length() != 8) {
            return "商品号格式错误";
        }

        // 浮点数直接比较（== 判断浮点数）
        double price = getPrice(productId);
        if (price == 0.0) {  // 浮点数 == 比较不安全
            return "商品价格异常";
        }

        // 业务逻辑：直接拼接 SQL（注入风险）
        String sql = "INSERT INTO orders(user_id, product_id, count) VALUES('"
            + userId + "','" + productId + "'," + count + ")";
        try {
            return doInsert(sql) ? "成功" : "失败";
        } catch (Exception e) {
            // 吞异常
        }
        return "失败";
    }

    /**
     * 批量查询订单 —— 集合未判空 + N+1
     */
    public List<String> batchGetOrders(List<String> userIds) {
        List<String> all = new java.util.ArrayList<>();
        for (String uid : userIds) {
            // 循环内查数据库（N+1）
            List<String> orders = queryOrders(uid);
            // orders 可能为 null，未判空
            all.addAll(orders);
        }
        return all;
    }

    /**
     * 计算总价 —— 浮点数累加精度问题 + 边界未处理
     */
    public double calcTotal(List<Double> prices, List<Integer> counts) {
        double total = 0;
        // 两个 list 长度可能不一致，未做校验
        for (int i = 0; i < prices.size(); i++) {
            // 浮点数直接累加会有精度问题
            total += prices.get(i) * counts.get(i);
        }
        // total 可能溢出，未做上限判断
        return total;
    }

    /**
     * 取消订单 —— 状态判断遗漏
     */
    public boolean cancelOrder(String orderId, int status) {
        // 只处理了 1=待支付，遗漏了 2=已支付、3=已发货等状态
        if (status == 1) {
            String sql = "UPDATE orders SET status = 9 WHERE id = '" + orderId + "'";
            try {
                return doUpdate(sql);
            } catch (Exception e) {
                // 吞异常
            }
        }
        return false;
    }

    // 模拟方法
    private double getPrice(String productId) {
        if (productId == null) return 0;
        return 9.9;
    }

    private boolean doInsert(String sql) { return true; }
    private boolean doUpdate(String sql) { return true; }
    private List<String> queryOrders(String uid) {
        if (uid == null) return null;
        return new java.util.ArrayList<>();
    }
}
