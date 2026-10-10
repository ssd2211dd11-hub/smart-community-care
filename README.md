# 智慧社区居家养老服务与告警协同平台

面向社区居家养老场景的 Java Web 项目，提供老人健康记录与异常事项、社区服务预约、履约确认和评价，以及家电开关模拟。

当前交付范围是桌面 Web 核心版本。健康步数、30/90 天及自定义概览、完整规则报告和健康传感器自动采样尚未完成，不能将本版本视为完整 V1.3 交付。家电状态来自平台内置模拟器，健康样例用于演示。

## 功能与实现

- **三角色与权限**：老人、社区管理员、服务人员；BCrypt 密码存储、Sa-Token 会话、接口角色检查及订单/事项/设备的对象归属校验。
- **健康记录与事项**：当前测量、历史补录、记录作废、七日概览；异常规则形成待核实事项，保留规则快照和处理时间线。
- **服务预约**：管理员维护服务与未来时段，老人预约，服务人员主动领取或由管理员分派；开工、结果提交、返工、确认、评价、取消及异常终止各有明确状态约束。
- **一致性保护**：创建预约时固定服务/时段锁顺序，条件更新名额；请求键与内容摘要防重；状态动作校验读取版本；订单快照和业务日志在事务内落库。
- **家电模拟**：电灯、空调、窗帘开关；设备独立启动/停止，自动心跳、延迟执行、失败一次、不回报、到期未确认与指令历史。提交成功仅表示指令已保存，页面随后显示执行结果。
- **桌面页面**：角色工作台、老人档案与账号管理、事项详情、订单、设备、资料与密码入口；列表使用服务端分页，BIGINT 标识符在 JSON 中以字符串传输。

```mermaid
flowchart LR
  A[老人预约] --> B[待领取或分派]
  B --> C[服务人员接单]
  C --> D[开始服务]
  D --> E[提交结果]
  E --> F[老人确认]
  F --> G[一次评价]
  E -->|反馈未完成| D
```

## 技术与目录

后端使用 Java 17、Spring Boot 4.1.1、MyBatis-Plus 3.5.17（Boot 4 starter）、Sa-Token 1.46.0 和 MySQL 8.0。会话保存在应用内存中，后端重启后需重新登录。前端使用 Vue 3、Vue Router、Pinia、Axios、Element Plus、ECharts 与 Vite 5，具体依赖版本以 `pom.xml` 和 `frontend/package-lock.json` 为准。

```text
.
├── pom.xml                      # 后端构建
├── config/
│   └── application-local.example.yml
├── database/
│   ├── schema.sql               # 仅供新建空库安装
│   ├── seed-demo.sql            # 虚构演示样例，仅首次空库使用
│   └── migrations/              # 人工审阅的增量 SQL
├── src/main/                    # Spring 后端与公开配置
├── src/test/                    # 后端回归测试与浏览器辅助脚本
└── frontend/
    ├── src/                     # Vue 页面
    └── test/                    # Node 行为测试
```

以上目录就是仓库根，无需再进入同名子目录。运行所需的配置示例、SQL 和回归测试均在仓库内；本机凭据、数据库备份和开发过程材料不属于公开工程。

## 本机运行

需要 JDK 17、Maven 3.8+、Node.js 18+ 和 MySQL 8.0。浏览器辅助回归脚本额外使用 Node.js 22 的 WebSocket 与本机 Chrome。后端端口为 `8890`，前端开发端口为 `5273`；端口已占用时应先核实进程归属。

### 1. 首次安装数据库

在仓库根进入 MySQL 客户端：

```sh
mysql --user=root --password --default-character-set=utf8mb4
```

以下内容在 MySQL 客户端执行，只用于**新建空库**：

```sql
CREATE DATABASE smart_community_care
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE smart_community_care;
SOURCE database/schema.sql;
SOURCE database/seed-demo.sql;

CREATE DATABASE smart_community_care_test
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE smart_community_care_test;
SOURCE database/schema.sql;
```

已有数据请先备份并审阅结构差异，不要重复执行建表或种子脚本。`seed-demo.sql` 的重复执行会改写演示记录、时段占用与事项状态；增量 SQL 也不能在已有相同列的库中重复执行。应用配置为 `spring.sql.init.mode=never`，启动时不会自动建表或灌入演示数据。

### 2. 配置连接

复制 `config/application-local.example.yml` 为 `config/application-local.yml`。PowerShell 示例：

```powershell
Copy-Item config/application-local.example.yml config/application-local.yml
```

编辑本地文件，将 `CHANGE_ME` 改为本机数据库账号和密码，或按示例表达式设置 `CARE_DB_USERNAME`、`CARE_DB_PASSWORD`。本地文件被 Git 忽略，位于资源目录外，不会打入 JAR。默认连接库为 `smart_community_care`，更换地址时应明确修改本地 `spring.datasource.url`。

示例文件启用家电模拟器。全局开启后，只有单独启动的设备才会运行；数据库中已经标为运行的模拟设备会在应用重新启动后继续调度。演示参数为每秒调度、5 秒心跳、20 秒离线阈值、2 秒执行延迟、30 秒指令有效期。

### 3. 启动后端与前端

在仓库根：

```sh
mvn spring-boot:run
```

Maven 启动插件会读取 `config/application-local.yml`。另开终端：

```sh
cd frontend
npm ci
npm run dev
```

访问 `http://127.0.0.1:5273`。Vite 将 `/api` 代理到 `http://127.0.0.1:8890`。首次执行演示种子后可使用以下虚构账号，口令仅供本机演示：

| 角色 | 用户名 | 演示口令 |
| --- | --- | --- |
| 社区管理员 | `admin` | `Admin@2026` |
| 服务人员 | `staff_wang` | `Staff@2026` |
| 老人 | `elder_zhang` / `elder_li` | `Elder@2026` |

服务预约只能选择未来开放时段；服务人员开始服务不能早于预约开始时间。管理员可以从页面新增未来时段，避免依赖已过期的样例安排。

### 4. 打包

```sh
mvn package -DskipTests
cd frontend
npm run build
```

前端产物位于 `frontend/dist/`。在仓库根运行后端 JAR：

```sh
java -Dspring.config.additional-location=file:config/application-local.yml -jar target/smart-community-care-0.0.1-SNAPSHOT.jar
```

`java -jar` 不继承 Maven 插件参数，需要显式传入外部配置。仓库未配置生产部署或自动发布流程。

## 验证与测试边界

不启动 Spring、不连接数据库的后端基础分页检查，以及前端行为检查：

```sh
mvn test "-Dtest=PageQueryTest"
cd frontend
npm test
```

其余后端集成测试使用真实 MySQL 的 `smart_community_care_test`，创建和清理明确标记的夹具。测试基类会在写入/清理前校验实际库名。**运行前应使用可恢复的专用测试库并核对其已有数据**；部分用例对管理员数量有前提，已有联调账号可能改变这些前提。

```sh
mvn test "-Dcare.simulator.schedule-interval-ms=3600000"
```

显式的测试调度间隔用于避免本地模拟配置覆盖测试配置后启动自动写入。设备相关集成用例按测试时钟/显式执行器控制场景，不能据此声称正常启动的调度已验收。`*IT` 浏览器用例还需要真实前端、Chrome 和独立浏览器配置，不应在共享业务库上直接运行；浏览器脚本及其 Java 驱动保留在 `src/test/`。

本机已有核心验收证据：后端 377 项集成检查、前端 36 项行为检查、真实 HTTP 32 个检查点、正常家电调度 23 个检查点，以及独立 Chrome 42 个页面检查点（含重复登录和准备检查）。浏览器覆盖三角色权限、预约到评价、防重与容量边界、三类家电正常/失败/未确认场景、健康异常与告警展示，并查看了关键页面的 1366×768 和 1920×1080 截图。

上述结果对应本机核心范围，不表示完整需求、全部页面分支、专项键盘操作、移动端、压力测试或生产性能指标。上传整理阶段只运行安全构建、无数据库写入的分页测试和前端测试；未重新运行会写入/清理数据库的完整集成套件。
