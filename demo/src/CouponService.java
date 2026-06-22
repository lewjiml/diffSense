import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 优惠券服务 —— V1.0 初始实现
 * 对应需求文档「## 优惠券管理」板块下的四个子需求：
 *   1. 创建优惠券
 *   2. 发放优惠券
 *   3. 核销优惠券
 *   4. 查询用户优惠券
 *
 * 为了 demo 清晰，使用内存存储，不接数据库。
 */
public class CouponService {

    // ============ 内存存储 ============
    private final Map<String, Coupon> couponStore = new HashMap<>();      // couponId -> Coupon
    private final Map<String, UserCoupon> userCouponStore = new HashMap<>(); // userCouponId -> UserCoupon
    private int idSeq = 1;

    // ============ 数据模型 ============
    static class Coupon {
        String id;
        String name;
        double faceValue;        // 面值
        double threshold;        // 适用门槛（满 N 元可用）
        LocalDateTime startTime;
        LocalDateTime endTime;
        int totalCount;          // 发放总量
        int remainingCount;      // 剩余库存
    }

    static class UserCoupon {
        String id;
        String userId;
        String couponId;
        String status;           // UNUSED / USED / EXPIRED
        LocalDateTime receivedAt;
    }

    // ============================================================
    // 子需求 1：创建优惠券
    // ============================================================
    public String createCoupon(String name, double faceValue, double threshold,
                               LocalDateTime startTime, LocalDateTime endTime, int totalCount) {
        // 校验：名称不能为空
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("优惠券名称不能为空");
        }
        // 校验：名称不超过 20 字
        if (name.length() > 20) {
            throw new IllegalArgumentException("优惠券名称不能超过20字");
        }
        // 校验：面值必须大于 0
        if (faceValue <= 0) {
            throw new IllegalArgumentException("面值必须大于0");
        }
        // 校验：适用门槛不得小于 0
        if (threshold < 0) {
            throw new IllegalArgumentException("适用门槛不得小于0");
        }
        // 校验：结束时间必须大于开始时间
        if (endTime == null || startTime == null || !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("结束时间必须大于开始时间");
        }
        // 校验：发放总量必须大于 0
        if (totalCount <= 0) {
            throw new IllegalArgumentException("发放总量必须大于0");
        }

        Coupon c = new Coupon();
        c.id = "C" + (idSeq++);
        c.name = name;
        c.faceValue = faceValue;
        c.threshold = threshold;
        c.startTime = startTime;
        c.endTime = endTime;
        c.totalCount = totalCount;
        c.remainingCount = totalCount;
        couponStore.put(c.id, c);
        return c.id;
    }

    // ============================================================
    // 子需求 2：发放优惠券
    // ============================================================
    public int grantCoupon(String couponId, List<String> userIds) {
        // 校验：优惠券必须存在
        Coupon coupon = couponStore.get(couponId);
        if (coupon == null) {
            throw new IllegalArgumentException("优惠券不存在: " + couponId);
        }
        // 校验：用户 ID 列表不能为空
        if (userIds == null || userIds.isEmpty()) {
            throw new IllegalArgumentException("用户ID列表不能为空");
        }

        int granted = 0;
        for (String userId : userIds) {
            // 每个用户限领 1 张同种优惠券（防重复领取）
            boolean alreadyHas = userCouponStore.values().stream()
                .anyMatch(uc -> uc.userId.equals(userId) && uc.couponId.equals(couponId));
            if (alreadyHas) {
                continue; // 已领取，跳过
            }
            // 发放数量不能超过剩余库存
            if (coupon.remainingCount <= 0) {
                break;
            }
            UserCoupon uc = new UserCoupon();
            uc.id = "UC" + (idSeq++);
            uc.userId = userId;
            uc.couponId = couponId;
            uc.status = "UNUSED";
            uc.receivedAt = LocalDateTime.now();
            userCouponStore.put(uc.id, uc);
            // 扣减库存
            coupon.remainingCount--;
            granted++;
        }
        return granted;
    }

    // ============================================================
    // 子需求 3：核销优惠券
    // ============================================================
    public double redeemCoupon(String userCouponId, double orderAmount) {
        UserCoupon uc = userCouponStore.get(userCouponId);
        if (uc == null) {
            throw new IllegalArgumentException("用户优惠券不存在: " + userCouponId);
        }
        Coupon coupon = couponStore.get(uc.couponId);
        if (coupon == null) {
            throw new IllegalStateException("优惠券主数据丢失");
        }
        // 校验：在有效期内
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.startTime) || now.isAfter(coupon.endTime)) {
            throw new IllegalStateException("优惠券不在有效期内");
        }
        // 校验：订单金额必须满足适用门槛
        if (orderAmount < coupon.threshold) {
            throw new IllegalStateException("订单金额未达到适用门槛");
        }
        // 计算优惠金额：订单金额 - 面值（最低为 0）
        double discount = Math.min(coupon.faceValue, orderAmount);
        double actualPay = orderAmount - discount;
        if (actualPay < 0) actualPay = 0;
        // 标记为已使用
        uc.status = "USED";
        return actualPay;
    }

    // ============================================================
    // 子需求 4：查询用户优惠券
    // ============================================================
    public List<UserCoupon> queryUserCoupons(String userId, String statusFilter) {
        List<UserCoupon> list = userCouponStore.values().stream()
            .filter(uc -> uc.userId.equals(userId))
            // 支持按状态过滤（statusFilter 为 null/空时返回全部）
            .filter(uc -> statusFilter == null || statusFilter.trim().isEmpty()
                          || statusFilter.equals(uc.status))
            .collect(Collectors.toList());
        // 按领取时间倒序排列
        list.sort(Comparator.comparing((UserCoupon uc) -> uc.receivedAt).reversed());
        return list;
    }
}
