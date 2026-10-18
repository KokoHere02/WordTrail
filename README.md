# WordTrail

单模块后端：Java 21、Spring Boot 3.5.7、MyBatis-Flex 1.11.4、MySQL 8、JJWT 0.12.6、独立 jBCrypt 0.4、Jakarta Validation、Lombok。

认证使用 Authorization Bearer Access Token 和请求体中的不透明 Refresh Token，适用于网页和移动端。运行依赖已移除 Spring Security，认证不建立 Session、不设置登录 Cookie、不使用 CSRF Token。

## 目录

```text
src/main/java/com/wordtrail/
├── WordTrailApplication.java
├── common/
│   ├── response/ApiResponse.java
│   └── exception/{BusinessException,GlobalExceptionHandler}.java
├── config/WebConfig.java
├── security/
│   ├── JwtTokenService.java
│   ├── AuthInterceptor.java
│   ├── CurrentUser.java
│   └── PasswordService.java
└── user/
    ├── controller/AuthController.java
    ├── service/UserService.java
    ├── mapper/{UserMapper,RefreshTokenMapper}.java
    ├── entity/{UserAccount,RefreshToken}.java
    ├── dto/{RegisterRequest,LoginRequest,RefreshTokenRequest}.java
    └── vo/{UserVO,TokenVO}.java

src/main/resources/
├── application.yml
└── db/
    ├── WordTrail.sql
    └── 002_create_refresh_token.sql
```

保留工作区当前的 com.wordtrail 包名，复用原单模块工程。业务持久层全部通过 MyBatis-Flex BaseMapper / QueryWrapper，不使用原生 JDBC、JdbcClient、JdbcTemplate、JPA 或 MyBatis-Plus，不需要 XML SQL。

## 数据库迁移

原 user_account 和 WordTrail.sql 保持不变。新增独立迁移：
`src/main/resources/db/002_create_refresh_token.sql`。

该文件只 CREATE refresh_token，字段为 id、user_id、token_hash、expires_at、revoked_at、created_at；token_hash 唯一，user_id 外键引用既有用户表。所有 Token 时间由应用按 UTC 写入/比较。

连接已存在的 word_trail_db 后执行一次：

```powershell
mysql --host=127.0.0.1 --port=3306 --user=root --password word_trail_db --execute="source src/main/resources/db/002_create_refresh_token.sql"
```

命令交互式提示输入数据库密码。应用保持 spring.sql.init.mode=never，不自动建表、删除数据库或修改既有表。此迁移已在本次开发的本机 MySQL 中执行，已有 refresh_token 表的环境无需重复执行。

## 配置与启动

需要 JDK 21、Maven 3.6.3+、MySQL 8。默认数据库 127.0.0.1:3306/word_trail_db，用户名/密码 root/root，支持环境变量覆盖。

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| DB_URL | 本机 word_trail_db JDBC URL | 数据库地址 |
| DB_USERNAME / DB_PASSWORD | root / root | 数据库凭证 |
| SERVER_PORT | 8080 | HTTP 端口 |
| JWT_SECRET_BASE64 | 无 | 必填，至少 32 个安全随机字节的标准 Base64 |
| JWT_ISSUER | wordtrail | 签发者，签发和校验必须一致 |
| JWT_ACCESS_TTL | 30m | Access Token 有效期，至少 1 秒 |
| REFRESH_TOKEN_TTL | 7d | 每次新签发 Refresh Token 的有效期，至少 1 秒 |
| CORS_ALLOWED_ORIGINS | 空 | 明确来源列表，逗号分隔，例如 http://localhost:5173 |

签名算法固定为 HS256，校验器仅允许 HS256，校验签名、issuer、exp，以及必需的 sub、iat、jti。sub 是经过验证的正整数用户 ID。JWT 仅含身份和标准时间/签发元数据，不含密码、哈希或 Refresh Token。无签名 Token、其他算法、过期、格式错误、错误签名或错误签发者均无法认证。

必须先配置签名密钥。开发时可用操作系统安全随机数生成器设置当前终端环境变量，命令不输出密钥：

```powershell
$jwtKeyBytes = New-Object byte[] 32
$jwtRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$jwtRng.GetBytes($jwtKeyBytes)
$jwtRng.Dispose()
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($jwtKeyBytes)

$env:JAVA_HOME = 'D:\java\jdk\21' # 替换为本机 JDK 21
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -version
mvn spring-boot:run
```

正式环境应安全保存同一强密钥并通过环境变量注入，不要每次启动重新生成或提交到代码仓库。改变密钥会使旧 Access Token 无效。缺少密钥、Base64 非法或解码少于 32 字节时启动失败并明确说明配置问题，应用没有固定备用密钥。

打包运行：

```shell
mvn clean verify
java -jar target/WordTrail-1.0.jar
```

## 接口与返回

统一返回 code、message、data。UserVO 只有 id、username、nickname；DTO/Entity/TokenVO 不生成含敏感字段的 toString，密码和哈希不出现在响应。登录/刷新成功响应的 data 为：

```text
{
  "accessToken": "<JWT>",
  "refreshToken": "<opaque random token>",
  "tokenType": "Bearer",
  "expiresIn": 1800,
  "user": {"id": 1, "username": "demo_user", "nickname": "Demo"}
}
```

| 方法及路径 | JSON 请求体 | 成功结果 |
| --- | --- | --- |
| POST /api/auth/register | username、password、可选 nickname | 201，用户信息；不自动登录 |
| POST /api/auth/login | username、password | 200，Token 对及用户信息 |
| POST /api/auth/refresh | refreshToken | 200，新的 Token 对及用户信息 |
| GET /api/auth/me | 无；Authorization: Bearer ... | 200，当前用户信息 |
| POST /api/auth/logout | refreshToken | 200，data 为 null；重复退出成功 |

注册、登录、刷新、退出不需要有效 Access Token，附带无效/过期 Access Token 也不会阻止这些公开 POST。其他接口默认要求 Bearer 认证；必要静态资源只放行 GET /assets/**、/favicon.ico。Token 不能通过 URL 或 Cookie 认证。

- username：4～32 位 ASCII 英文字母、数字、下划线，统一小写存储并忽略大小写判重。
- password：8～64 个 Unicode 字符，保留所有空格；nickname 最长 64 个 Unicode 字符。
- 参数不合法：400 / INVALID_PARAMETER，只返回校验消息，不包含拒绝值。
- 重复账号及并发唯一键冲突：409 / USERNAME_EXISTS，“用户名已存在”。
- 有效格式的账号不存在或密码错误：401 / BAD_CREDENTIALS，“账号或密码错误”。
- Access Token 缺失、格式非法、过期或校验失败：统一 JSON 401 / UNAUTHENTICATED。
- Refresh Token 未知、过期或已经消费/撤销：401 / INVALID_REFRESH_TOKEN。缺少 refreshToken 请求字段则为 400。
- 退出对于已撤销或未知 Refresh Token 均返回成功，不泄露该 Token 是否存在。

## 调用示例（PowerShell）

以下示例不输出 Token，所有认证值只在 Authorization 请求头或 JSON 请求体中传递。重复注册时更换用户名。

```powershell
$base = 'http://localhost:8080/api/auth'

# 注册；不需要 Cookie 或 CSRF Token
$registration = Invoke-RestMethod "$base/register" -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body (@{username='demo_user'; password='example_password'; nickname='Demo'} | ConvertTo-Json)

# 登录；将返回值赋给变量，不在终端打印
$login = Invoke-RestMethod "$base/login" -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body (@{username='demo_user'; password='example_password'} | ConvertTo-Json)
$tokens = $login.data

# 当前用户；只从已校验的 JWT sub 取得身份
$me = Invoke-RestMethod "$base/me" `
    -Headers @{Authorization="Bearer $($tokens.accessToken)"}

# 刷新；只需 Refresh Token，不依赖 Access Token
$rotated = Invoke-RestMethod "$base/refresh" -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body (@{refreshToken=$tokens.refreshToken} | ConvertTo-Json)
$tokens = $rotated.data # 同时替换两个 Token；旧 Refresh Token 已失效

# 新 Access Token 可继续使用
$me = Invoke-RestMethod "$base/me" `
    -Headers @{Authorization="Bearer $($tokens.accessToken)"}

# 退出；必须提交当前保存的最新 Refresh Token
$logout = Invoke-RestMethod "$base/logout" -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body (@{refreshToken=$tokens.refreshToken} | ConvertTo-Json)

# 客户端删除两个 Token，停止使用旧 Access Token
$tokens = $null
$login = $null
$rotated = $null
```

网页 fetch 示例：

```javascript
const base = 'http://localhost:8080/api/auth';
const login = await fetch(base + '/login', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({username: 'demo_user', password: 'example_password'})
}).then(r => r.json());
let tokens = login.data;

const me = await fetch(base + '/me', {
  headers: {Authorization: 'Bearer ' + tokens.accessToken}
}).then(r => r.json());

const refreshed = await fetch(base + '/refresh', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({refreshToken: tokens.refreshToken})
}).then(r => r.json());
if (refreshed.code === 'OK') tokens = refreshed.data;

await fetch(base + '/logout', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({refreshToken: tokens.refreshToken})
});
tokens = null;
```

跨域时配置 CORS_ALLOWED_ORIGINS 为网页的明确来源。服务端允许 Authorization、Content-Type 请求头和预检，不允许任意来源，不启用 Cookie 凭证传递。此方案不需要 credentials: 'include' 或 CSRF Token。手机端同样发送 Bearer 请求头即可。

## Refresh Token、安全边界与用户隔离

Refresh Token 使用 SecureRandom 生成 64 个随机字节，编码为无填充 Base64URL（86 字符）。数据库只存标准 SHA-256 的 64 位十六进制哈希，原始值仅在签发时返回，不记录日志。

每次登录创建独立记录，刷新时用事务将旧行条件更新为 revoked_at 非空，并创建新记录。条件同时要求未撤销且未过期，MySQL 行锁保证同一值并发刷新最多一个成功；失败会回滚撤销和新记录。客户端应串行刷新，并同时替换两个 Token。

退出只撤销请求中 Refresh Token 对应记录，不使用 Access Token 推断或撤销其他设备。已轮换的旧 Token 无法撤销替代它的新 Token，客户端退出时应提交最新值。

**退出不会立即撤销已签发的 Access Token，它在剩余有效期内仍可能被使用（默认最长约 30 分钟）。** 本版接受该限制，不使用 Redis 或黑名单。客户端退出后必须删除两个 Token。

独立 jBCrypt 使用 cost 12。普通密码直接 BCrypt；为兼容原有密码记录及支持超过 72 个 UTF-8 字节的 Unicode 密码，保留既有 {bcrypt-sha256} 格式，使用 JDK 标准 SHA-256 + Base64 预处理后交由 jBCrypt 哈希，没有自制加密算法。

业务构造器注入 CurrentUser 并调用 id()，后续数据查询必须约束此用户 ID，不信任客户端 user_id。AuthInterceptor 每次请求开始先移除残留 ThreadLocal，认证后绑定身份，在请求完成（含控制器异常）以及异步交接时 remove；未通过认证的请求不绑定身份。

禁止在 URL、访问日志、异常日志、请求/响应体日志或调试输出中记录 Token、密码、哈希。应用保持 MyBatis SQL 参数日志关闭，统一异常处理不输出敏感请求对象或底层 SQL/JJWT 错误内容。

## 验证

```shell
mvn verify
mvn -Dspring.profiles.active=mysql-test verify
```

默认使用 H2 独立测试表；mysql-test 使用已存在的 MySQL 表，关闭初始化。测试签名密钥由测试动态生成安全随机值，不会成为运行时默认配置。测试账号随机创建，只按测试记录的 ID 清理自身 Refresh Token 和用户记录，不清理其他数据或改动表结构。

20 项测试覆盖：注册及非法参数、唯一键兜底、密码错误及 Unicode、JWT 必需声明、算法/签发者/签名/过期校验、缺少密钥拒绝启动、刷新成功/失效/并发单次消费/事务回滚、退出幂等、设备隔离、Access Token 退出后仍有效的边界、身份隔离、ThreadLocal 完成/异常/异步清理、Cookie 不可认证和 CORS。

本次实际执行结果：Java 21 下 H2 测试 20 项通过；MySQL 8.0.39 的 mysql-test 测试 20 项通过，均为 0 失败、0 错误、0 跳过。MySQL 的 user_account 和 refresh_token 测试前后记录数均为 0。另以缺少签名密钥启动实际 JAR，应用明确报告 JWT_SECRET_BASE64 is required 并以非零状态退出。依赖树和可执行包检查无 Spring Security，源码扫描无 Session/CSRF 或 JDBC 业务实现，已生成 target/WordTrail-1.0.jar。
