import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户服务类 —— 演示用，故意包含多种缺陷，供 AI 初审 demo 展示。
 * 注意：本文件所有问题都是有意植入的，请勿在生产代码中参考。
 */
public class UserService {

    // 硬编码数据库密码（安全风险）
    private String dbPassword = "root123456";

    /**
     * 根据 userId 查询用户 —— SQL 拼接注入 + 资源泄漏
     */
    public ResultSet getUser(String userId) {
        try {
            Connection conn = DriverManager.getConnection(
                "jdbc:mysql://localhost:3306/test", "root", dbPassword);
            Statement stmt = conn.createStatement();
            // SQL 拼接注入风险
            String sql = "SELECT * FROM users WHERE id = '" + userId + "'";
            return stmt.executeQuery(sql);
            // conn / stmt 未关闭，资源泄漏
        } catch (Exception e) {
            // 吞异常
        }
        return null;
    }

    /**
     * 查询所有用户 —— 集合元素未判空
     */
    public List<String> getUsers(List<String> ids) {
        List<String> users = new ArrayList<>();
        for (String id : ids) {
            String name = queryName(id); // u 可能为 null
            users.add(name);             // 未判空直接 add
        }
        return users;
    }

    /**
     * 循环内查数据库（N+1 问题）
     */
    public List<String> queryNames(List<String> ids) {
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            // 每条 id 都执行一次查询 —— 经典 N+1
            names.add(queryName(id));
        }
        return names;
    }

    /**
     * 创建用户 —— 入参未校验 + 敏感信息打印日志
     */
    public boolean createUser(String phone, String email, int age) {
        // 手机号直接打印到日志（敏感信息泄露）
        System.out.println("创建用户，手机号：" + phone);
        // 未校验 phone / email / age 是否合法
        try {
            Connection conn = DriverManager.getConnection(
                "jdbc:mysql://localhost:3306/test", "root", dbPassword);
            Statement stmt = conn.createStatement();
            String sql = "INSERT INTO users(phone, email, age) VALUES('" + phone + "','" + email + "'," + age + ")";
            int rows = stmt.executeUpdate(sql);
            return rows > 0;
        } catch (Exception e) {
            // 吞异常
        }
        return false;
    }

    /**
     * 更新年龄 —— 逻辑错误（赋值写成比较）
     */
    public boolean updateAge(String userId, int age) {
        if (age = 0) { // 致命：赋值写成比较，且 age=0 恒为 false 走不到
            return false;
        }
        try {
            Connection conn = DriverManager.getConnection(
                "jdbc:mysql://localhost:3306/test", "root", dbPassword);
            Statement stmt = conn.createStatement();
            String sql = "UPDATE users SET age = " + age + " WHERE id = '" + userId + "'";
            return stmt.executeUpdate(sql) > 0;
        } catch (Exception e) {
            // 吞异常
        }
        return false;
    }

    /**
     * 删除用户 —— 未校验入参
     */
    public boolean deleteUser(String userId) {
        try {
            Connection conn = DriverManager.getConnection(
                "jdbc:mysql://localhost:3306/test", "root", dbPassword);
            Statement stmt = conn.createStatement();
            String sql = "DELETE FROM users WHERE id = '" + userId + "'";
            return stmt.executeUpdate(sql) > 0;
        } catch (Exception e) {
            // 吞异常
        }
        return false;
    }

    /**
     * 计算折扣 —— 业务逻辑分支遗漏
     * level 取值：0=普通, 1=银卡, 2=金卡, 3=钻石
     */
    public double calcDiscount(int level) {
        if (level == 0) return 1.0;
        if (level == 1) return 0.95;
        if (level == 2) return 0.9;
        if (level == 3) return 0.8;
        // 遗漏：level < 0 或 level > 3 时无处理，返回 0 导致计算错误
        return 0;
    }

    /**
     * 分页查询 —— 分页参数未校验
     */
    public List<String> listUsers(int page, int size) {
        // page=0 时 offset 为负
        int offset = (page - 1) * size;
        List<String> result = new ArrayList<>();
        try {
            Connection conn = DriverManager.getConnection(
                "jdbc:mysql://localhost:3306/test", "root", dbPassword);
            Statement stmt = conn.createStatement();
            String sql = "SELECT name FROM users LIMIT " + size + " OFFSET " + offset;
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                result.add(rs.getString("name"));
            }
        } catch (Exception e) {
            // 吞异常
        }
        return result;
    }

    // 模拟按 id 查询用户名，可能返回 null
    private String queryName(String id) {
        if (id == null) return null;
        if (id.equals("0")) return null;
        return "user-" + id;
    }
}
