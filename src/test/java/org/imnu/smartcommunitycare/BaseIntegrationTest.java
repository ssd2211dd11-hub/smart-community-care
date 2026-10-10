package org.imnu.smartcommunitycare;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceItemMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.user.mapper.SysUserMapper;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 集成测试基类。
 *
 * <p>关键点：
 * <ul>
 *   <li>连<b>专用测试库</b> {@code smart_community_care_test}，不碰开发演示库</li>
 *   <li>每个用例前把会话清空，避免 Sa-Token 的 token 缓存跨用例泄漏</li>
 *   <li>不依赖 @Transactional 回滚：Sa-Token 与事务不同步，回滚不等于清会话，
 *       混用会出现"数据库已回滚但会话还在"的诡异失败</li>
 *   <li>清理入口<b>自身校验库名</b>，不依赖多个前置方法的执行顺序</li>
 *   <li>清理<b>只删夹具</b>：账号按字面量 {@code it_} 前缀（转义下划线），
 *       服务按登记 ID 或 {@code IT-FIXTURE} 标记</li>
 * </ul>
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired
    protected SysUserMapper userMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected BCryptPasswordEncoder passwordEncoder;

    @Autowired
    protected ElderProfileMapper elderProfileMapper;

    /**
     * 服务 Mapper，<b>刻意收窄为 private</b>。
     *
     * <p>六个 order 测试类原先都直接 {@code itemMapper.insert(item)} 建夹具服务，
     * 那样可以绕过登记——漏登记不会报错，只会表现为「库里莫名多了一条数据」。
     * 当前测试通过 {@link #createFixtureItem} 统一创建服务夹具，
     * 工厂内写标记并登记；其他 Mapper 或 JDBC 路径并未被禁止。
     *
     * <p>需要判断某服务是否为夹具时用 {@link #isFixtureItem(Long)}，
     * 不要重新把 mapper 暴露出来。
     */
    @Autowired
    private ServiceItemMapper itemMapper;


    /** 演示账号明文密码，与 seed 保持一致 */
    protected static final String ADMIN_PASSWORD = "Admin@2026";
    protected static final String ELDER_PASSWORD = "Elder@2026";
    protected static final String STAFF_PASSWORD = "Staff@2026";

    /**
     * 测试库名。清理入口用它校验实际连接的库，不匹配则中止。
     *
     * <p>写死而不是从配置读：读配置意味着「配置写错时也会跟着错」，
     * 而这里要的是一个独立于配置的判断依据。
     */
    protected static final String TEST_DATABASE = "smart_community_care_test";

    /**
 * 测试夹具账号的字面量前缀。
     *
     * <p><b>注意这不是一个模式，仅是字面量。</b>清理时按
     * {@code username LIKE 'it\_%'}（下划线被转义）匹配，
     * 因此 {@code italian_keeper} 这类以 {@code it} 开头、
     * 第二字符不是下划线的用户名<b>不会</b>被命中。
     *
     * <p>不用 {@code it%}：那会连 {@code italian_keeper} 一起删掉。
     * 这是本轮退回修正的重点之一。
     */
    protected static final String FIXTURE_PREFIX = "it_";

    /**
     * 夹具账号的 SQL LIKE 前缀，<b>下划线已转义</b>。
     *
     * <p>为什么不能直接写 {@code LIKE 'it_%'}：SQL LIKE 里 {@code _} 是
     * 「匹配任意单个字符」，{@code it_} 会同时命中 {@code italian_keeper}
     * （{@code it} + {@code a}）、{@code item_master}（{@code it} + {@code e}）。
     * 转义成 {@code it\_%} 才是字面量匹配。
     *
     * <p>同样不能用 MyBatis-Plus 的 {@code likeRight()}：它不转义下划线，
     * 生成的仍是 {@code LIKE 'it_%'}。本轮回归用例
     * {@code similarUsernameIsNotTreatedAsFixture} 正是为此而写，
     * 且它在首次运行时确实抓到了这个缺陷。
     */
    protected static final String FIXTURE_SQL_PREFIX = "it\\_%";

    /**
     * 夹具服务的明确标记：写入 {@code service_item.description}。
     *
     * <p><b>不用 provider 前缀匹配</b>。曾用 {@code provider LIKE '测试%'}，
     * 但 provider 是业务字段，真实数据完全可能取「测试机构」这类名称，
     * 批量删会误伤。<br>
     * 现在改为：{@link #registerFixtureItem} 登记过的 ID 才删，
     * 未登记的一律保留。
     */
    protected static final String FIXTURE_ITEM_MARK = "IT-FIXTURE";

    /**
     * 本轮测试登记的夹具服务 ID。
     *
     * <p>登记而非推断：只有显式登记过的 ID 才会被清理。
     * 库里的其他服务——哪怕 provider 恰好叫「测试机构」——都不动。
     *
     * <p>跨轮次累积：上一轮跑完残留的夹具服务 ID 需要下一轮也能清掉，
     * 所以登记信息在 JVM 内一直保留，且下次启动时会先扫库
     * 找出带 {@link #FIXTURE_ITEM_MARK} 标记的服务并登记。
     */
    private static final Set<Long> FIXTURE_ITEM_IDS =
            Collections.synchronizedSet(new LinkedHashSet<>());

    /** 从登录响应中提取 data.token 的值 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 确认当前连接的确实是测试库。
     *
     * <p>profile 名叫 test 不代表连对了库——URL 写错就会连到开发库并清空真实数据。
     * 这个断言必须在每个需要写库的测试前生效。
     *
     * <p>这里不清理 Sa-Token 会话：@BeforeEach 阶段上下文尚未初始化，
     * 调 StpUtil 会抛 SaTokenContextException。每个用例用自己的账号登录，
     * 令牌天然隔离，不需要额外清理。
     */
    @BeforeEach
    void prepareFixtureCleanState() {
        // 只保留这一个 @BeforeEach 做清理。
        //
        // 早期版本拆成两个：先 assertConnectedToTestDatabase，再 cleanPreviousTestAccounts。
        // 那样"库名校验"只是恰好排在"删除"前面的另一个前置方法——
        // JUnit 不保证多个 @BeforeEach 之间的相对顺序（继承层级、框架实现都可能变），
        // 一旦顺序调换，删除就会在校验之前执行。
        //
        // 现在校验被写进 clearAllData 内部，删除与校验绑定成一个不可拆的动作。
        // 这里只负责"踢会话"——它必须在删账号前做，且与删除无关，
        // 所以留在外层反而更清楚。
        staleFixtureUserIds().forEach(StpUtil::logout);
        clearAllData();
    }

    /** 上一轮遗留的夹具账号 ID，用于踢掉其已签发的令牌 */
    private List<Long> staleFixtureUserIds() {
        // 使用参数化LIKE匹配字面量it_前缀；转义下划线，不匹配相似的非夹具账号。
        return jdbcTemplate.queryForList(
                "SELECT id FROM sys_user WHERE username LIKE ?",
                Long.class, FIXTURE_SQL_PREFIX);
    }

    /**
     * 在测试库中创建一个账号，返回其 ID。
     */
    protected Long createAccount(String username, String password, UserRole role, boolean enabled) {
        SysUser existing = userMapper.findByUsername(username);
        if (existing != null) {
            // 删账号前先踢会话：令牌与用户 ID 绑定，账号没了令牌就是悬空引用
            StpUtil.logout(existing.getId());
            // 该账号可能已建档（外键 fk_elder_profile_user），需先清干净再删账号
            clearAllData();
            userMapper.deleteById(existing.getId());
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRealName("测试-" + username);
        user.setRole(role.name());
        user.setEnabled(enabled);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user.getId();
    }

    /**
     * 创建一个老人账号并同时建立老人档案，返回档案 ID。
     *
     * <p>业务上老人账号与档案是一对一且同事务创建（见 AuthService.register），
     * 但 {@link #createAccount} 只建账号。涉及按档案归属取数的测试
     *（预约、告警、健康概览）必须用这个方法，否则会得到
     * 「当前账号尚未建立老人档案」。
     */
    protected Long createElder(String username) {
        return createElder(username, ELDER_PASSWORD);
    }

    /** 创建老人账号并建立档案，指定密码 */
    protected Long createElder(String username, String password) {
        Long userId = createAccount(username, password, UserRole.ELDER, true);
        ElderProfile profile = new ElderProfile();
        profile.setUserId(userId);
        profile.setBirthDate(java.time.LocalDate.of(1960, 1, 1));
        profile.setGender("male");
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        elderProfileMapper.insert(profile);
        return profile.getId();
    }

    /**
     * 登录并返回令牌。
     *
     * <p><b>通过真实 HTTP 接口登录，而不是调StpUtil.login()</b>：
     * Sa-Token 的上下文绑定在线程上，MockMvc 请求由框架在指定线程处理，
     * 测试代码里直接调 StpUtil 会抛"上下文尚未初始化"。
     * 走接口既绕开线程问题，也顺带验证了登录链路本身。
     *
     * @param perform POST /api/auth/login 的执行器，由子类提供带 MockMvc 的实现
     */
    protected String loginAndGetToken(MockMvc mockMvc, String username, String password)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();

        return extractToken(result.getResponse().getContentAsString());
    }


    /**
     * 从登录响应中取出令牌。
     *
     * <p><b>刻意用正则而不是 JSON 库</b>：Boot 4 把Jackson 2 换成了 Jackson 3
     * （包名 {@code com.fasterxml.jackson} → {@code tools.jackson}），
     * 任何在测试里硬编码序列化库的写法都会在框架升级时先坏掉。
     * 令牌是简单字符串字段，正则足够，也最不挑环境。
     */
    private String extractToken(String responseBody) {
        Matcher matcher = TOKEN_PATTERN.matcher(responseBody);
        if (!matcher.find()) {
            throw new IllegalStateException("登录响应中未找到 token 字段：" + responseBody);
        }
        return matcher.group(1);
    }

    /**
     * 校验明文密码与数据库中的 BCrypt 哈希是否匹配。
     *
     * <p>用于确认 seed 脚本写入的哈希可用——"文件里有哈希"不等于"能登录"。
     */
    /**
     * 正常动作夹具模拟先读取订单，再携带所读版本提交。
     * 已显式指定版本的请求原样保留；缺失/旧版本契约测试直接构造请求，不调用本辅助。
     */
    protected String withOrderVersion(Object orderId, String body) {
        String json = body == null ? "{}" : body.trim();
        if (json.contains("\"version\"")) {
            return json;
        }
        Integer version = jdbcTemplate.query("SELECT version FROM service_order WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, orderId);
        return json.substring(0, json.length() - 1) + (json.length() > 2 ? "," : "")
                + "\"version\":" + version + "}";
    }

    protected boolean passwordMatches(String username, String plaintext) {
        SysUser user = userMapper.findByUsername(username);
        return user != null && passwordEncoder.matches(plaintext, user.getPasswordHash());
    }

    /**
     * 清理测试夹具数据，<b>只删明确属于夹具的行</b>。
     *
     * <p><b>第一件事就是校验实际库名</b>，且校验放在方法内部而不是依赖
     * 某个 {@code @BeforeEach} 排在前面：把校验写进被调用方，
     * 无论谁在什么顺序下调它，删除都不会先于校验发生。
     * 早期版本把校验放在另一个 {@code @BeforeEach} 里，
     * 只要 JUnit 的执行顺序变化、或者有人直接调用本方法，
     * 就可能在库名校验之前完成删除。
     *
     * <p><b>删除范围限定为夹具</b>：每条 DELETE 都带 WHERE，
     * 命中的是「与 {@code it_} 前缀账号相关联的数据」或「名称含『测试』的服务」。
     * 测试库里若有人工放入的非夹具数据（联调证据、排查用的样本），
     * 跑一次测试之后仍在。这不是额外功能：全表 DELETE 的前提
     * 只是「库里只有夹具」，而这个前提没有任何机制保证。
     */
    protected void clearAllData() {
        clearAllDataExpecting(TEST_DATABASE);
    }

    /**
     * 按指定的期望库名执行清理，<b>库名不符立即中止，不执行任何删除</b>。
     *
     * <p>把期望库名做成参数，是为了让「校验失败时零删除」这条要求
     * <b>可以被行为验证</b>：测试传一个错误的库名，观察数据是否原封不动。
     * 只断言「校验方法会抛错」是不够的——它不能证明删除动作
     * 确实被挡在了校验之后。早期版本没有这个参数，只能靠读代码确认顺序，
     * 那不算验证。
     *
     * @param expectedDatabase 期望的库名；与实际不符则抛异常且不删任何数据
     */
    protected void clearAllDataExpecting(String expectedDatabase) {
        // 校验必须是本方法的第一条语句。
        // 顺序在这里是安全属性，不是风格问题——把它挪到任何一条删除之后，
        // 这个类的全部保护就失效了。
        assertDatabaseIs(expectedDatabase);

        deleteFixtureRows();
    }

    /**
     * 删除夹具行。调用方必须已经完成库名校验。
     *
     * <p>单独拆出来，是为了让「校验」与「删除」在代码结构上分开：
     * 看到 {@code deleteFixtureRows} 的调用点，就能确认它前面一定有过校验，
     * 因为校验写在它唯一的上层入口里。
     */
    private void deleteFixtureRows() {

        // 夹具账号与其档案是所有业务数据的根，按外键倒序逐层删到它
        // （外键方向：anomaly -> record -> alert -> order -> review -> device -> profile -> user）
        //
        // 先取出夹具账号 ID，后续条件统一用 IN (...)，而不是各表各写一遍
        // 「LIKE 'it\_%'」子查询——那样每个表都要重新解释一次夹具的定义。
        List<Long> fixtureUserIds = fixtureUserIds();
        String userIds = joinIds(fixtureUserIds);

        // 老人档案：挂在夹具账号下
        if (!userIds.isEmpty()) {
            jdbcTemplate.update("DELETE a FROM health_record_anomaly a"
                    + " JOIN health_record r ON r.id = a.health_record_id"
                    + " WHERE r.elder_id IN (SELECT id FROM elder_profile WHERE user_id IN (" + userIds + "))");
            jdbcTemplate.update("DELETE FROM alert_event_log WHERE event_id IN"
                    + " (SELECT id FROM alert_event WHERE elder_id IN"
                    + " (SELECT id FROM elder_profile WHERE user_id IN (" + userIds + ")))");
            jdbcTemplate.update("DELETE FROM alert_event WHERE elder_id IN"
                    + " (SELECT id FROM elder_profile WHERE user_id IN (" + userIds + "))");
            jdbcTemplate.update("DELETE FROM health_record WHERE elder_id IN"
                    + " (SELECT id FROM elder_profile WHERE user_id IN (" + userIds + "))");
            jdbcTemplate.update("DELETE FROM service_review WHERE order_id IN"
                    + " (SELECT id FROM service_order WHERE elder_id IN"
                    + " (SELECT id FROM elder_profile WHERE user_id IN (" + userIds + ")))");
            jdbcTemplate.update("DELETE FROM service_order_log WHERE order_id IN"
                    + " (SELECT id FROM service_order WHERE elder_id IN"
                    + " (SELECT id FROM elder_profile WHERE user_id IN (" + userIds + ")))");
            jdbcTemplate.update("DELETE FROM service_order WHERE elder_id IN"
                    + " (SELECT id FROM elder_profile WHERE user_id IN (" + userIds + "))");
            jdbcTemplate.update("DELETE FROM device_command WHERE device_id IN"
                    + " (SELECT id FROM device WHERE elder_id IN"
                    + " (SELECT id FROM elder_profile WHERE user_id IN (" + userIds + ")))");
            jdbcTemplate.update("DELETE FROM device WHERE elder_id IN"
                    + " (SELECT id FROM elder_profile WHERE user_id IN (" + userIds + "))");
            jdbcTemplate.update("DELETE FROM elder_profile WHERE user_id IN (" + userIds + ")");
            // 夹具账号本身也属于夹具：档案删完后账号才可以删（外键 fk_elder_profile_user）。
            // 不放在 finally 里——校验失败时必须一条 DELETE 都不执行。
            //
            // 用 ? 传参而不是把模式内联进 SQL：
            // 内联时 Java 的 \\_ 到 MySQL 解析会被当转义符，实际等于 LIKE 'it_%'，
            // italian_keeper 这类用户名会被一起删掉。
            // 参数传递下 "it\\_%" 才保持字面量含义（已用探针实测三种写法的匹配数）。
            jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE ?",
                    FIXTURE_SQL_PREFIX);
        }

        // 服务只按登记ID或明确IT-FIXTURE标记清理，不用名称或提供方猜测夹具。
        deleteRegisteredFixtureItems();

        // 操作日志按操作人过滤：夹具账号的操作留痕属于夹具。
        // 按 operator_id 精确匹配，不用文本模式——reason 是自由文本，猜不得
        if (!userIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM operation_log WHERE operator_id IN (" + userIds + ")");
        }
    }

    /**
     * 清理夹具服务：登记过的 ID + 带明确标记的。
     *
     * <p>标记 {@link #FIXTURE_ITEM_MARK} 写在 description 里，
     * 它不是业务词汇，真实数据不会恰好取这个值。
     */
    private void deleteRegisteredFixtureItems() {
        // 扫库登记：让上一轮残留的夹具服务在本轮也能被清掉，
        // 否则登记集合随 JVM 重启清空，残留会一直留着。
        // 用 LIKE 而非等值：各测试类的 description 是
        // 「用途描述 [IT-FIXTURE]」，标记是后缀。
        jdbcTemplate.queryForList(
                "SELECT id FROM service_item WHERE description LIKE ?",
                Long.class, "%" + FIXTURE_ITEM_MARK + "%")
                .forEach(FIXTURE_ITEM_IDS::add);

        // 记录清理前存在的服务，清理后与之比对——
        // 这样「本该是夹具却没登记」的服务会被发现，而不是静默残留
        List<Long> beforeIds = jdbcTemplate.queryForList(
                "SELECT id FROM service_item", Long.class);

        if (FIXTURE_ITEM_IDS.isEmpty()) {
            // 没有登记也没有标记时仍要检查孤儿：
            // 否则「什么都不知道」与「没有需要清的」两种情况无法区分
            reportLeftoverItems(beforeIds);
            return;
        }
        String ids = String.join(",", FIXTURE_ITEM_IDS.stream()
                .map(String::valueOf).toList());

        // 依赖倒序：有订单的时段随订单一起删（上面已按夹具账号处理），
        // 这里删的是「无订单但属于夹具」的时段
        jdbcTemplate.update("DELETE sl FROM service_slot sl"
                + " WHERE sl.service_id IN (" + ids + ")");
        jdbcTemplate.update("DELETE FROM service_item WHERE id IN (" + ids + ")");
        FIXTURE_ITEM_IDS.clear();

        reportLeftoverItems(beforeIds);
    }

    /**
     * 报告清理后仍然存在的服务。
     *
     * <p><b>不抛异常，只记日志。</b>测试库里本来可能有人工放置的数据，
     * 清理不该动它们；把这种情况当错误会让「有人放了非夹具数据」这件事
     * 无法存在。这里只提示：这些服务既没被登记也没带标记，
     * <b>如果它们本该是夹具，说明创建者忘了登记</b>。
     *
     * <p>为什么需要这个检查：登记制是约定而非强制，
     * 新写测试时漏调 {@link #registerFixtureItem} 不会立刻暴露，
     * 只表现为「库里莫名其妙多了一条数据」，很难联想到是漏登记。
     * 至少让它变成一条看得见的日志。
     *
     * @param beforeIds 清理前存在的服务 ID
     */
    private void reportLeftoverItems(List<Long> beforeIds) {
        if (beforeIds.isEmpty()) {
            return;
        }
        List<Long> after = jdbcTemplate.queryForList(
                "SELECT id FROM service_item", Long.class);
        List<Long> leftover = after.stream()
                .filter(id -> beforeIds.contains(id))
                .toList();
        if (!leftover.isEmpty()) {
            log.warn("清理后仍有服务未被删除：{}。"
                    + "若它们是测试夹具，说明创建时忘了调 registerFixtureItem"
                    + " 或没在 description 里写 {} 标记；"
                    + "若是有意保留的非夹具数据，可忽略本条。",
                    leftover, FIXTURE_ITEM_MARK);
        }
    }

    /**
     * 登记一个夹具服务，清理时会删掉它。
     *
     * <p>由创建夹具服务的测试显式调用。<b>不登记就不会被删</b>——
     * 这是本轮的设计前提：清理范围由创建者声明，不由代码推断。
     *
     * @param serviceId 服务 ID
     */
    protected void registerFixtureItem(Long serviceId) {
        if (serviceId != null) {
            FIXTURE_ITEM_IDS.add(serviceId);
        }
    }

    /**
     * 创建一个夹具服务：带明确标记并自动登记，清理时必删。
     *
     * @return 服务 ID
     */
    protected Long createFixtureItem() {
        return createFixtureItem(null);
    }

    /**
     * 创建并登记夹具服务，回调后写入IT-FIXTURE标记，避免自定义字段覆盖标记。
     * 私有Mapper只限制直接访问；其他Mapper和JDBC仍需遵守夹具登记规则。
     * @param customizer 插入前的字段自定义，可为 null
     * @return 服务 ID
     */
    protected Long createFixtureItem(java.util.function.Consumer<ServiceItem> customizer) {
        ServiceItem item = new ServiceItem();
        item.setName("夹具服务-" + java.util.UUID.randomUUID());
        item.setCategory("HOUSEKEEPING");
        item.setPrice(new java.math.BigDecimal("50.00"));
        item.setProvider("夹具提供方");
        item.setEnabled(true);
        item.setSortOrder(1);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        if (customizer != null) {
            customizer.accept(item);
        }
        // 标记最后追加：用途描述照留，标记一定在
        item.setDescription(appendFixtureMark(item.getDescription()));
        itemMapper.insert(item);
        registerFixtureItem(item.getId());
        return item.getId();
    }

    /**
     * 在用途描述后追加夹具标记。
     *
     * <p>description 是有实际内容的业务字段（演示库里是「擦拭家具、地面清洁」这类），
     * 夹具也该有可读的用途描述——排查时能看出是哪条用例造的。
     * 但清理要靠它命中，因此标记必须追加在后面。
     *
     * @param desc 用途描述，可为空
     * @return 「用途描述 [IT-FIXTURE]」；用途为空时只留标记
     */
    private String appendFixtureMark(String desc) {
        String mark = "[" + FIXTURE_ITEM_MARK + "]";
        if (desc == null || desc.isBlank()) {
            return mark;
        }
        // 已带标记就不重复追加
        return desc.contains(FIXTURE_ITEM_MARK) ? desc : desc + " " + mark;
    }

    /**
     * 断言当前连接的库名等于期望值，否则中止。
     *
     * <p>抽成带参方法是为了能被测试直接调用：验证「非测试库会被拒绝」
     * 时不需要真的去改数据源指向开发库——那既危险又难清理。
     * 测试传入一个错误的库名即可触发同一条判断路径。
     *
     * @param expected 期望的库名
     * @throws IllegalStateException 实际库名不符时
     */
    protected void assertDatabaseIs(String expected) {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "测试连接到了非测试库：" + actual + "，已中止以免破坏开发数据");
        }
    }

    /** 当前测试库中属于夹具的账号 ID（{@code it_} 前缀） */
    private List<Long> fixtureUserIds() {
        // 使用参数化LIKE匹配字面量it_前缀；转义下划线，不匹配相似的非夹具账号。
        return jdbcTemplate.queryForList(
                "SELECT id FROM sys_user WHERE username LIKE ?",
                Long.class, FIXTURE_SQL_PREFIX);
    }

    /** ID 列表拼成 {@code IN (...)} 片段；空列表返回空串让调用方跳过该条 */
    private String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }
}
