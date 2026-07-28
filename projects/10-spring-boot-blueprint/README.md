# 10 · Spring Boot Blueprint

Khác với các project 01–09 (mỗi project học 1 chủ đề riêng lẻ), đây là **blueprint production-ready**: bộ khung chuẩn để luyện viết REST API hoàn chỉnh với Spring Data JPA + Spring Security, đủ tiêu chuẩn để làm nền cho một dự án thực tế — không phải bài học đơn lẻ.

## Mục tiêu

- Luyện tập toàn bộ vòng đời viết API production: layered/module architecture, security (JWT), validation, error handling chuẩn, caching, testing pyramid, observability, CI/CD.
- Dùng làm **template tham khảo** khi bắt đầu project Spring Boot thật sau này.

## Tech stack

| Thành phần | Lựa chọn |
|---|---|
| Java | 21 (LTS) |
| Spring Boot | 4.1.0 |
| Web | Spring Web MVC (`spring-boot-starter-webmvc`) |
| Data | Spring Data JPA + MySQL (`mysql-connector-j`) |
| Security | Spring Security |
| Validation | Bean Validation (`spring-boot-starter-validation`) |
| Cache | Redis (đã có `RedisConfig`, chưa thêm dependency — xem TODO) |
| Boilerplate | Lombok |
| Build | Maven Wrapper (`./mvnw`) |

## Cấu trúc thư mục

Nguyên tắc tổ chức: **package theo module (feature)**, không phải theo layer thuần tuý. Cái gì thuộc riêng 1 nghiệp vụ → nằm trong `module/<tên>/`; cái gì dùng chung ≥ 2 module → nằm ở package top-level dùng chung (`common/`, `security/`, `config/`...).

```
src/main/java/com/maaitlunghau/__spring_boot_blueprint/
├── Application.java
│
├── common/                      # Dùng chung TOÀN app — không thuộc riêng module nào
│   ├── dto/
│   │   └── ApiResponse.java         # envelope response chuẩn: status, message, data, timestamp
│   └── entity/
│       └── BaseEntity.java          # id, createdAt, updatedAt, version — mọi entity extend từ đây
│
├── config/                      # @Configuration bean — không chứa logic nghiệp vụ
│   ├── SecurityConfig.java          # SecurityFilterChain, PasswordEncoder, AuthenticationManager
│   ├── CorsConfig.java              # CorsConfigurationSource
│   ├── RedisConfig.java             # RedisTemplate, connection factory
│   └── OpenApiConfig.java           # Swagger/OpenAPI docs bean
│
├── security/                    # Hạ tầng xác thực — dùng chung, KHÔNG đặt trong module/auth
│   └── (JwtService, JwtAuthenticationFilter, CustomUserDetailsService,
│        CustomAuthenticationEntryPoint, CustomAccessDeniedHandler — chưa tạo, xem TODO)
│
├── exception/
│   └── GlobalExceptionHandler.java  # @RestControllerAdvice — nên trả ProblemDetail (RFC 7807)
│
├── filter/                      # Servlet filter cấp request — chưa có file
│   └── (CorrelationIdFilter, RequestLoggingFilter — xem TODO)
│
├── aspect/
│   └── LoggingAspect.java           # AOP: log entry/exit + thời gian chạy method
│
├── scheduler/
│   └── CleanupScheduledTask.java    # @Scheduled job (vd: purge soft-delete)
│
├── util/
│   ├── CookieUtils.java
│   └── DateUtils.java
│
└── module/                      # Feature module — mỗi module tự chứa đủ layer của nó
    ├── auth/
    │   ├── controller/v1/
    │   ├── service/
    │   ├── repository/
    │   ├── entity/
    │   ├── dto/{request,response}/
    │   └── mapper/
    └── user/
        ├── controller/v1/
        ├── service/
        │   └── impl/                # UserServiceImpl — interface UserService nằm thẳng trong service/
        ├── repository/
        │   └── spec/                # Specification<T> cho dynamic filter/search
        ├── entity/
        ├── dto/{request,response}/
        └── mapper/
```

**Quy ước module:** interface service đặt trực tiếp trong `service/` (vd `UserService.java`), implementation trong `service/impl/`. Không tạo folder tên `interface` — đây là từ khoá reserved trong Java, dùng làm tên package sẽ không compile được.

## Resources

```
src/main/resources/
└── application.yml   # profile mặc định — TODO: tách application-dev.yml / application-prod.yml
```

Chưa có `db/migration/` (Flyway) — hiện đang chạy `ddl-auto: update`, chỉ phù hợp dev, **không dùng cho production**.

## Root

| File | Trạng thái |
|---|---|
| `Dockerfile` | Đã tạo, **rỗng** — cần multi-stage build (maven build → JRE runtime) |
| `docker-compose.yml` | Đã tạo, **rỗng** — cần MySQL (+ Redis khi bật cache) cho local dev |
| `.env.example` | Đã tạo, **rỗng** — liệt kê tên biến môi trường, không chứa giá trị thật |
| `.github/workflows/ci.yml` | Đã tạo, **rỗng** — cần chạy `mvn verify` trên mỗi PR |
| `.github/workflows/cd.yml` | Đã tạo, **rỗng** — deploy pipeline |

## Chạy

```bash
cd projects/10-spring-boot-blueprint
./mvnw spring-boot:run
# http://localhost:8081
```

MySQL cần chạy sẵn (username `root` / password `112233`, DB `spring-boot-blueprint`, xem `application.yml`) — chưa có `docker-compose.yml` để tự động hoá việc này.

## TODO — chưa hoàn thiện

- [ ] Implement `SecurityConfig` (JWT stateless), `security/` package đầy đủ (JwtService, JwtAuthenticationFilter, UserDetailsService, entry point/access-denied handler)
- [ ] Implement `GlobalExceptionHandler` trả `ProblemDetail` (RFC 7807) thay vì tự chế error shape
- [ ] Thêm `filter/CorrelationIdFilter` — gắn `traceId` vào MDC cho mỗi request
- [ ] Thêm dependency: `spring-boot-starter-actuator`, `flyway-mysql`, `spring-boot-starter-data-redis` (đang có `RedisConfig` rỗng — sẽ lỗi bean nếu bật mà thiếu dependency), `springdoc-openapi-starter-webmvc-ui`, `testcontainers` (test scope)
- [ ] Viết migration Flyway đầu tiên (`db/migration/V1__...sql`), đổi `ddl-auto` sang `validate`
- [ ] Tách `application-dev.yml` / `application-prod.yml`, secrets qua biến môi trường
- [ ] Điền nội dung `Dockerfile`, `docker-compose.yml`, `.env.example`, `.github/workflows/{ci,cd}.yml`
- [ ] Viết entity/dto/controller/service/repository đầu tiên cho `module/user` (CRUD user) và `module/auth` (register/login/refresh)
- [ ] Test: unit (service, Mockito), slice (`@WebMvcTest`, `@DataJpaTest`), integration (`@SpringBootTest` + Testcontainers)
