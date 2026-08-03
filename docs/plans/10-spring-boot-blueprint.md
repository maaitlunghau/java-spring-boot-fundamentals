# Plan — Project 10: Spring Boot Blueprint

> **Loại tài liệu:** Kế hoạch (roadmap) — phản ánh những gì **đã quyết định**, chưa implement. Cập nhật lần cuối 2026-07-30.
> Khác với project 01–09 (mỗi project học 1 chủ đề), project 10 là **blueprint production-ready**: khung chuẩn để luyện viết REST API hoàn chỉnh với Spring Data JPA + Spring Security, đủ tiêu chuẩn làm nền cho dự án thực tế (chuẩn bị cho project ở trường).

---

## 1. Mục tiêu & Tại sao

- Luyện lại toàn bộ vòng đời viết API production, nhưng lần này **tự thiết kế từ đầu** thay vì follow theo hướng dẫn từng bước như 01–09.
- Có nền C#/Node.js-Express từ trước → tập trung vào phần **idiom riêng của Spring** (Spring Security filter chain, Spring Data JPA, DI qua constructor) thay vì học lại khái niệm REST/JWT cơ bản.
- 2 module trọng tâm: **`user`** (quản lý user, CRUD chuẩn) và **`auth`** (authentication nâng cao — JWT access + refresh token lưu DB, rotation, reuse detection, multi-device session).
- **Quyết định lưu trữ (2026-07-30):** refresh token lưu ở **MySQL** qua JPA Entity — giống hướng của project 06/09, đây là pattern chuẩn/phổ biến nhất cho refresh token (durable, không mất khi Redis restart/evict). Redis chỉ giữ vai trò lưu trạng thái của **access token** (blacklist khi logout, Phase 6) và rate-limit counter — dữ liệu chấp nhận mất được (TTL ngắn, mất thì chỉ ảnh hưởng tối đa vài phút), đúng chỗ Redis mạnh nhất. Đổi lại việc lưu DB không có TTL tự động như Redis, nên Phase 4 có thêm 1 scheduled job dọn refresh token hết hạn/đã revoke.

---

## 2. Tech Stack

| Concern | Lựa chọn | Ghi chú |
|---|---|---|
| Framework | Spring Boot 4.1.0 | Web MVC (`spring-boot-starter-webmvc`) |
| Ngôn ngữ | Java 21 | |
| Security | `spring-boot-starter-security` | JWT stateless thuần — **không** bật `oauth2Login()` (tránh session/CSRF churn đã gặp ở project 09) |
| JWT | JJWT `jjwt-api/impl/jackson` 0.13.0 | Đồng bộ version với project 09 |
| Persistence | `spring-boot-starter-data-jpa` + MySQL | Specification API cho filter động; **refresh token cũng lưu ở đây** (Phase 4) |
| Cache | `spring-boot-starter-data-redis` | Access-token blacklist (logout), rate limit login — dữ liệu ngắn hạn, chấp nhận mất được (Phase 6) |
| Validation | `spring-boot-starter-validation` | Bean Validation trên DTO |
| Docs | `springdoc-openapi-starter-webmvc-ui` | Swagger UI (Phase 8) |
| Migration | `flyway-mysql` | Thay `ddl-auto: update` (Phase 8) |
| Test | Testcontainers | Integration test trên MySQL/Redis thật (Phase 8) |

---

## 3. Cấu trúc package (đã chốt)

```
com/maaitlunghau/__spring_boot_blueprint/
├── common/            # dùng chung toàn app: ApiResponse, BaseEntity, PageResponse
├── config/             # SecurityConfig, CorsConfig, RedisConfig, OpenApiConfig
├── security/           # JwtService, JwtAuthenticationFilter, UserDetailsServiceImpl, entry point/access-denied handler
├── exception/          # GlobalExceptionHandler + custom exceptions
├── filter/             # RateLimitFilter
├── aspect/             # LoggingAspect (AOP)
├── scheduler/          # job định kỳ
├── util/               # RequestUtils, DateUtils
└── module/
    ├── auth/    {controller/v1, service, repository, dto/{request,response}}
    └── user/    {controller/v1, service/impl, repository/spec, entity, dto/{request,response}}
```

Quy ước: cái gì thuộc riêng 1 nghiệp vụ → `module/<tên>/`; cái gì dùng chung ≥ 2 module → package top-level. Interface service đặt thẳng trong `service/`, implementation trong `service/impl/`.

---

## 4. Kiến trúc tổng quan

```mermaid
flowchart TB
    Client["Client (Postman/mobile/SPA khác)<br/>Authorization: Bearer &lt;access_token&gt;"]

    subgraph Backend["Spring Boot Backend :8081"]
        RL["RateLimitFilter<br/>chặn brute-force /login"]
        Filter["JwtAuthenticationFilter<br/>đọc header, check Redis blacklist"]
        Sec["SecurityConfig — STATELESS<br/>@PreAuthorize theo role/ownership"]
        AuthMod["module/auth<br/>register / login / refresh / logout / sessions"]
        UserMod["module/user<br/>CRUD + phân quyền"]
    end

    subgraph Infra["Docker infra"]
        MySQL[("MySQL 8 :3306<br/>users, refresh_tokens")]
        Redis[("Redis 7 :6379<br/>access-token blacklist,<br/>rate limit")]
    end

    Client -->|Bearer token| RL --> Filter --> Sec
    Sec --> AuthMod
    Sec --> UserMod
    AuthMod -->|blacklist| Redis
    RL -->|counter| Redis
    AuthMod -->|user lookup, refresh token, session| MySQL
    UserMod --> MySQL
```

---

## 5. Lộ trình theo Phase (tổng quan)

| Phase | Nội dung | Module |
|---|---|---|
| 1 | `User` entity + repository | user |
| 1+ | CRUD `user` hoàn chỉnh (list/filter/pagination, xem, tạo, sửa, xoá) — **chưa auth/phân quyền** | user |
| 2 | Register + login + JWT access token (chưa refresh, chưa Redis) | auth |
| 3 | Wire `SecurityConfig` + `JwtAuthenticationFilter` toàn app — STATELESS | auth |
| 4 | Refresh token (MySQL/JPA) + rotation/reuse detection | auth |
| 5 | Bổ sung `@PreAuthorize` theo role/ownership lên CRUD đã có từ Phase 1+ | user |
| 6 | Logout/blacklist, rate limit login, multi-device session | auth |
| 7 | Test (unit/slice/integration) cả 2 module | auth + user |
| 8 | Polish: Flyway, OpenAPI, Docker/CI | cả 2 |

Chi tiết từng phase — **từng step, full code** — ở mục 6 bên dưới.

---

## 6. Chi tiết từng Phase

### Phase 1 — `User` entity + repository

**Mục tiêu:** có data model nền tảng, chưa cần security.

**Step 1.1 — `common/entity/BaseEntity.java`**

> Dùng Lombok `@Getter` (không phải `@Data`) trên entity — `.claude/rules/tech-defaults.md` cấm `@Data` trên entity vì nó sinh `equals()`/`hashCode()` theo TẤT CẢ field (kể cả quan hệ lazy → trigger fetch ngoài ý muốn, dễ `StackOverflowError` với quan hệ 2 chiều) và sinh setter cho mọi field (phá encapsulation — entity trong blueprint này chỉ mutate qua domain method như `updateProfile()`/`changeRole()`, không có setter công khai). `@Getter` đơn thuần an toàn: chỉ sinh getter, không đụng `equals`/`hashCode`/constructor/setter.

```java
package com.maaitlunghau.__spring_boot_blueprint.common.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;

/** Mọi entity trong app extend từ đây — id, audit timestamp, optimistic locking dùng chung. */
@Getter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
```

**Step 1.2 — `module/user/entity/Role.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.entity;

public enum Role {
    ADMIN,
    USER
}
```

**Step 1.3 — `module/user/entity/User.java`**

`User` implements `UserDetails` trực tiếp (giống project 09) — không cần adapter riêng.

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.entity;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.maaitlunghau.__spring_boot_blueprint.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "users")
@Getter // sinh getFullName()/getEmail()/getPassword()/getRole()/isEnabled() — KHÔNG sinh setter
public class User extends BaseEntity implements UserDetails {

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false)
    private String password; // @Getter sinh getPassword() -> khớp thẳng chữ ký UserDetails.getPassword()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true; // field boolean -> @Getter sinh isEnabled() -> khớp thẳng UserDetails.isEnabled()

    protected User() {} // JPA yêu cầu no-arg constructor

    public User(String fullName, String email, String password, Role role) {
        this.fullName = fullName;
        this.email = email;
        this.password = password;
        this.role = role;
    }

    public void updateProfile(String fullName) {
        this.fullName = fullName;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    // ===== UserDetails — 2 method dưới đây Lombok KHÔNG tự sinh được (tên khác field / cần tính toán) =====

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email; // UserDetails coi "username" là email — không có field riêng tên "username"
    }
}
```

> `isAccountNonExpired`/`isAccountNonLocked`/`isCredentialsNonExpired` của `UserDetails` có default method trả `true` sẵn — không cần override trừ khi cần khoá tài khoản sau này.
> `getPassword()` và `isEnabled()` không cần viết tay lẫn không cần `@Override` tường minh — method Lombok sinh ra từ field `password`/`enabled` đã đúng y hệt chữ ký mà interface `UserDetails` yêu cầu, tự động thoả mãn interface.

**Step 1.4 — `module/user/repository/UserRepository.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

`JpaSpecificationExecutor` cần cho Phase 5 (filter động qua `Specification`) — thêm luôn từ đầu để khỏi phải sửa lại interface sau.

**Verify Phase 1:** `./mvnw compile` phải chạy sạch (chưa có bean nào phụ thuộc DB thật nên chưa cần MySQL chạy).

---

### Phase 1+ — CRUD `user` hoàn chỉnh (chưa có auth/phân quyền)

**Mục tiêu:** có `UserController`/`UserService`/`UserRepository` chạy được full CRUD (list + filter + pagination, xem, tạo, sửa, xoá) **trước khi** đụng tới JWT — dễ test bằng curl, và dùng chính endpoint tạo user ở đây để tạo tài khoản ADMIN đầu tiên (chưa có register/login nên không có cách nào khác để có user trong DB).

> **Vì sao cần `SecurityConfig` ngay từ Phase này:** `spring-boot-starter-security` đã có sẵn trong `pom.xml` từ đầu. Có dependency này trên classpath mà **chưa khai báo `SecurityFilterChain` bean nào** thì Spring Boot tự động bật cấu hình bảo mật mặc định — sinh 1 password ngẫu nhiên in ra console và chặn **toàn bộ** endpoint bằng Basic Auth. Muốn test CRUD không-auth ở phase này, bắt buộc phải có 1 `SecurityConfig` permit-all tối thiểu — đây cũng chính là bean `PasswordEncoder`/`AuthenticationManager` mà Phase 2 (login) cần, nên làm luôn từ đây, Phase 2 không phải quay lại sửa `SecurityConfig` nữa.

**Step 1+.1 — `config/SecurityConfig.java`** (tạm thời permit-all — Phase 3 siết lại thành STATELESS + JWT)

```java
package com.maaitlunghau.__spring_boot_blueprint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfConfigurer;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(CsrfConfigurer::disable) // API thuần Bearer token, không dùng cookie -> không cần CSRF
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()); // TODO Phase 3: siết lại
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

**Step 1+.2 — Cập nhật lại toàn bộ `common/dto/ApiResponse.java`** — thêm factory `of()` để trả `status` HTTP khác 200 kèm data (vd `201 Created` kèm object vừa tạo)

```java
package com.maaitlunghau.__spring_boot_blueprint.common.dto;

import java.time.LocalDateTime;

public record ApiResponse<T>(int status, String message, T data, LocalDateTime timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "Success", data, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> of(int status, String message, T data) {
        return new ApiResponse<>(status, message, data, LocalDateTime.now());
    }

    public static ApiResponse<Void> message(int status, String message) {
        return new ApiResponse<>(status, message, null, LocalDateTime.now());
    }
}
```

**Step 1+.3 — `common/dto/PageResponse.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.common.dto;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean last
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
            page.getContent(), page.getNumber(), page.getSize(),
            page.getTotalElements(), page.getTotalPages(), page.isLast()
        );
    }
}
```

**Step 1+.4 — `module/user/dto/response/UserResponse.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.dto.response;

import java.time.LocalDateTime;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

public record UserResponse(
    Long id,
    String fullName,
    String email,
    String imageUrl,
    Role role,
    boolean enabled,
    LocalDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getId(), user.getFullName(), user.getEmail(), user.getImageUrl(),
            user.getRole(), user.isEnabled(), user.getCreatedAt()
        );
    }
}
```

> `imageUrl` có trong response vì entity `User` của bạn đã bổ sung field này ở Phase 1 — nếu bạn không có field đó thì bỏ dòng tương ứng.

**Step 1+.5 — `module/user/dto/request/CreateUserRequest.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;

public record CreateUserRequest(
    @NotBlank(message = "Họ tên là bắt buộc") String fullName,
    @Email(message = "Email không hợp lệ") @NotBlank(message = "Email là bắt buộc") String email,
    @NotBlank(message = "Mật khẩu là bắt buộc") @Size(min = 6, message = "Mật khẩu tối thiểu 6 ký tự") String password,
    @NotNull(message = "role là bắt buộc") Role role
) {}
```

**Step 1+.6 — `module/user/dto/request/UpdateProfileRequest.java` + `UpdateUserRoleRequest.java`**

> **Cập nhật 2026-07-29:** ban đầu Step này định làm 1 DTO gộp "sửa tất cả trong 1" (`UpdateUserRequest`) rồi tách ra ở Phase 5 khi có `@AuthenticationPrincipal`. Trong lúc triển khai thực tế đã quyết định tách DTO **ngay từ Phase 1+** để tránh viết rồi xoá — lý do: `UserServiceImpl` ghi đè field không điều kiện (không merge partial update), nên 1 DTO gộp bắt buộc mọi field phải `@NotBlank`/`@NotNull` cùng lúc, dễ nhầm với "phải luôn gửi đủ cả fullName lẫn role". Tách theo đúng ranh giới nghiệp vụ (hồ sơ vs quyền hạn) ngay từ đầu rõ ràng hơn. Phase 5 giờ chỉ còn việc gắn `@PreAuthorize`/`@AuthenticationPrincipal` lên 2 DTO/endpoint đã có sẵn này — xem Phase 5 bên dưới.
>
> **Cập nhật 2026-07-29 (2):** `UpdateProfileRequest` ban đầu bắt buộc cả `fullName` lẫn `imageUrl` (ngữ nghĩa PUT — thay toàn bộ). Nhận ra 2 field này độc lập với nhau trong cùng 1 concern "profile" (client có thể chỉ muốn đổi avatar mà không đổi tên) — đổi sang ngữ nghĩa PATCH: field nào không gửi (`null`) thì giữ nguyên giá trị cũ, `UserServiceImpl.updateProfile()` merge thủ công trước khi gọi `user.updateProfile(...)`. `UpdateUserRoleRequest`/`updateRole()` vẫn giữ PUT vì chỉ có 1 field, không có khái niệm "giữ nguyên phần còn lại".

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request;

public record UpdateProfileRequest(
    String fullName,
    String imageUrl
) {}
```

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request;

import jakarta.validation.constraints.NotNull;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;

public record UpdateUserRoleRequest(
    @NotNull(message = "Role is required.") Role role
) {}
```

**Step 1+.7 — Cập nhật lại toàn bộ `module/user/repository/UserRepository.java`** — thêm `countByRole` (cần cho rule "không xoá/hạ quyền ADMIN cuối cùng")

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    long countByRole(Role role);
}
```

**Step 1+.8 — `module/user/repository/spec/UserSpecifications.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.repository.spec;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

public final class UserSpecifications {

    private UserSpecifications() {}

    public static Specification<User> keywordIn(String keyword) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(keyword)) return cb.conjunction();
            String pattern = "%" + keyword.toLowerCase() + "%";
            return cb.or(
                cb.like(cb.lower(root.get("fullName")), pattern),
                cb.like(cb.lower(root.get("email")), pattern)
            );
        };
    }

    public static Specification<User> hasRole(Role role) {
        return (root, query, cb) -> role == null ? cb.conjunction() : cb.equal(root.get("role"), role);
    }
}
```

**Step 1+.9 — `module/user/service/UserService.java`** (interface)

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.service;

import org.springframework.data.domain.Pageable;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateUserRoleRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;

public interface UserService {
    PageResponse<UserResponse> search(String keyword, Role role, Pageable pageable);
    UserResponse getById(Long id);
    UserResponse create(CreateUserRequest request);
    UserResponse updateProfile(Long id, UpdateProfileRequest request);
    UserResponse updateRole(Long id, UpdateUserRoleRequest request);
    void delete(Long id);
}
```

**Step 1+.10 — `module/user/service/impl/UserServiceImpl.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.service.impl;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.__spring_boot_blueprint.exception.BadRequestException;
import com.maaitlunghau.__spring_boot_blueprint.exception.DuplicateResourceException;
import com.maaitlunghau.__spring_boot_blueprint.exception.ResourceNotFoundException;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateUserRoleRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.spec.UserSpecifications;
import com.maaitlunghau.__spring_boot_blueprint.module.user.service.UserService;

@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public PageResponse<UserResponse> search(String keyword, Role role, Pageable pageable) {
        Page<User> page = userRepository.findAll(
            UserSpecifications.keywordIn(keyword).and(UserSpecifications.hasRole(role)), pageable);
        return PageResponse.from(page.map(UserResponse::from));
    }

    @Override
    public UserResponse getById(Long id) {
        return UserResponse.from(findUserOrThrow(id));
    }

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already exists: " + request.email());
        }
        User user = new User(request.fullName(), request.email(),
            passwordEncoder.encode(request.password()), request.role());
        return UserResponse.from(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse updateProfile(Long id, UpdateProfileRequest request) {
        User user = findUserOrThrow(id);
        String fullName = request.fullName() != null ? request.fullName() : user.getFullName();
        String imageUrl = request.imageUrl() != null ? request.imageUrl() : user.getImageUrl();
        user.updateProfile(fullName, imageUrl);
        return UserResponse.from(user); // dirty checking tự flush khi transaction commit
    }

    @Override
    @Transactional
    public UserResponse updateRole(Long id, UpdateUserRoleRequest request) {
        User user = findUserOrThrow(id);
        if (user.getRole() == Role.ADMIN && request.role() != Role.ADMIN
                && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new BadRequestException("Cannot demote the last ADMIN in the system");
        }
        user.changeRole(request.role());
        return UserResponse.from(user);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        User user = findUserOrThrow(id);
        if (user.getRole() == Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new BadRequestException("Cannot delete the last ADMIN in the system.");
        }
        userRepository.delete(user);
    }

    private User findUserOrThrow(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }
}
```

**Step 1+.11 — `module/user/controller/v1/UserController.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.controller.v1;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateUserRoleRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.service.UserService;

import jakarta.validation.Valid;

/**
 * CHƯA có phân quyền — mọi endpoint đang public tạm thời vì chưa có JWT filter
 * (Phase 3) lẫn @AuthenticationPrincipal/@PreAuthorize thật. 2 endpoint update
 * đã tách theo đúng ranh giới nghiệp vụ (profile vs role) từ Phase 1+ này —
 * Phase 5 chỉ còn việc gắn @PreAuthorize lên đây + thêm endpoint /me, không
 * viết lại DTO/service/controller từ đầu nữa.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Role role,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(userService.search(keyword, role, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(201, "Created successfully", created));
    }

    @PatchMapping("/{id}/profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(@PathVariable Long id,
                                                                     @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Updated profile successfully", userService.updateProfile(id, request)));
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<ApiResponse<UserResponse>> updateRole(@PathVariable Long id,
                                                                  @Valid @RequestBody UpdateUserRoleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Updated role successfully", userService.updateRole(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.ok(ApiResponse.message(200, "Deleted successfully"));
    }
}
```

**Verify Phase 1+:** `./mvnw spring-boot:run` (cần MySQL sống). Tạo user ADMIN đầu tiên (chưa có register nên đây là cách duy nhất để có data):

```bash
curl -X POST localhost:8081/api/v1/users -H "Content-Type: application/json" \
  -d '{"fullName":"Admin","email":"admin@example.com","password":"password123","role":"ADMIN"}'

curl localhost:8081/api/v1/users
curl "localhost:8081/api/v1/users?keyword=admin&page=0&size=10"
curl -X PATCH localhost:8081/api/v1/users/1/profile -H "Content-Type: application/json" \
  -d '{"imageUrl":"http://example.com/avatar.png"}'
curl -X PUT localhost:8081/api/v1/users/1/role -H "Content-Type: application/json" \
  -d '{"role":"ADMIN"}'
```

Tất cả phải trả `200`/`201` không cần header `Authorization` (vì `SecurityConfig` đang permit-all).

---

### Phase 2 — Register + login + JWT access token

**Mục tiêu:** có auth chạy end-to-end bằng JWT access token thuần (chưa refresh, chưa Redis).

**Step 2.1 — Thêm dependency JJWT vào `pom.xml`**

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.13.0</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.13.0</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.13.0</version>
    <scope>runtime</scope>
</dependency>
```

**Step 2.2 — Thêm cấu hình JWT vào `application.yml`**

```yaml
app:
  jwt:
    secret: "ChangeThisToARandomSecretAtLeast32BytesLongForHS256!!"
    access-token-expiration: 900000                # 15 phút (ms)
    refresh-token-expiration: 604800000             # 7 ngày (ms) — idle TTL, dùng từ Phase 4
    refresh-token-absolute-expiration: 2592000000   # 30 ngày (ms) — absolute TTL, dùng từ Phase 4 (cập nhật 2026-07-30)
```

> `secret` chỉ để chạy dev. Khi lên `application-prod.yml` (Phase 8) phải đọc từ biến môi trường, không hardcode.
>
> **Cập nhật 2026-07-29:** `access-token-expiration` thật đang set `300000` (5 phút) thay vì `900000` (15 phút) như ví dụ trên — giá trị ngắn hơn để dễ test luồng hết hạn token bằng tay. Giá trị `secret` thật nằm trong `application.yml` (không duplicate lại ở đây).
>
> **Cập nhật 2026-07-30:** thêm `refresh-token-absolute-expiration` — trần cứng cho refresh token, không reset khi rotate (khác `refresh-token-expiration` là idle TTL, reset mỗi lần rotate). Xem Phase 4.

**Step 2.3 — `common/dto/ApiResponse.java`**

> **Cập nhật 2026-07-29:** đã làm sẵn từ Phase 1+ (Step 1+.2), không cần làm lại — giữ nguyên tham khảo dưới đây.

Envelope response dùng chung toàn app.

```java
package com.maaitlunghau.__spring_boot_blueprint.common.dto;

import java.time.LocalDateTime;

public record ApiResponse<T>(int status, String message, T data, LocalDateTime timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "Success", data, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data, LocalDateTime.now());
    }

    public static ApiResponse<Void> message(int status, String message) {
        return new ApiResponse<>(status, message, null, LocalDateTime.now());
    }
}
```

**Step 2.4 — Exception classes: `exception/AppException.java`**

> **Cập nhật 2026-07-29:** 4 exception class này (`AppException`, `ResourceNotFoundException`, `DuplicateResourceException`, `BadRequestException`) đã tạo sẵn từ Phase 1+ để `UserServiceImpl` dùng — không tạo lại ở Phase này. Message trong code thật dùng tiếng Anh (`"User not found: " + id`), khác bản tiếng Việt dưới đây — giữ bản dưới chỉ để tham khảo ý tưởng.

```java
package com.maaitlunghau.__spring_boot_blueprint.exception;

public abstract class AppException extends RuntimeException {
    protected AppException(String message) {
        super(message);
    }
}
```

**`exception/ResourceNotFoundException.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.exception;

public class ResourceNotFoundException extends AppException {
    public ResourceNotFoundException(String resource, Object identifier) {
        super(resource + " không tồn tại: " + identifier);
    }
}
```

**`exception/DuplicateResourceException.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.exception;

public class DuplicateResourceException extends AppException {
    public DuplicateResourceException(String message) {
        super(message);
    }
}
```

**`exception/BadRequestException.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.exception;

public class BadRequestException extends AppException {
    public BadRequestException(String message) {
        super(message);
    }
}
```

**Step 2.5 — `exception/GlobalExceptionHandler.java`**

> **Cập nhật 2026-07-29:** bản thật khác đáng kể so với draft ban đầu dưới đây — dùng message tiếng Anh, có thêm handler `MethodArgumentTypeMismatchException` (fix rough edge: query param sai kiểu enum trả `500` thay vì `400`, phát hiện lúc verify Phase 1+), và `BadCredentialsException` tách riêng (không gộp `DisabledException`, vì `User` chưa có field khoá tài khoản). **Chưa có** handler cho `AccessDeniedException`/`DisabledException` — để dành đúng lúc cần (`AccessDeniedException` chỉ có ý nghĩa từ Phase 5 khi `@PreAuthorize` bắt đầu chặn thật).

```java
package com.maaitlunghau.__spring_boot_blueprint.exception;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.message(404, ex.getMessage()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.message(409, ex.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.message(400, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.message(400, message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = String.format("%s: invalid value '%s'", ex.getName(), ex.getValue());
        if (ex.getRequiredType() != null && ex.getRequiredType().isEnum()) {
            message += " (expected one of: " + Arrays.toString(ex.getRequiredType().getEnumConstants()) + ")";
        }
        return ResponseEntity.badRequest().body(ApiResponse.message(400, message));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.message(401, "Invalid email or password"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        return ResponseEntity.internalServerError().body(ApiResponse.message(500, "Internal server error"));
    }
}
```

**Step 2.6 — DTO auth: `module/auth/dto/request/RegisterRequest.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "Full name is required.") String fullName,
    @Email(message = "Email is invalid.") @NotBlank(message = "Email is required.") String email,
    @NotBlank(message = "Password is required.") @Size(min = 6, message = "Password must be at least 6 characters long.") String password
) {}
```

**`module/auth/dto/request/LoginRequest.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @Email(message = "Email is invalid.") @NotBlank(message = "Email is required.") String email,
    @NotBlank(message = "Password is required.") String password
) {}
```

**`module/auth/dto/response/AuthResponse.java`** (bản tối giản — Phase 4 sẽ cập nhật lại thêm `refreshToken`)

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response;

public record AuthResponse(String accessToken, long expiresIn) {}
```

**Step 2.7 — `security/JwtService.java`**

> **Cập nhật 2026-07-29:** bản thật khớp gần như y hệt draft dưới đây, chỉ khác 1 điểm: `extractClaims()` khai `public` thay vì `private` (chưa có class nào khác gọi trực tiếp, nhưng để public sẵn không sai — cân nhắc thu hẹp lại `private` khi chắc chắn không cần dùng ngoài class). Từng bị bug thật ở `extractUsername()` (gọi nhầm `.getId()` thay vì `.getSubject()` — trả về `jti` thay vì email, làm `JwtAuthenticationFilter` ở Phase 3 luôn tìm sai user) — đã tự phát hiện và fix đúng trước khi merge.

```java
package com.maaitlunghau.__spring_boot_blueprint.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenExpirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.access-token-expiration}") long accessTokenExpirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(user.getEmail())
            .id(UUID.randomUUID().toString())
            .claim("role", user.getRole().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(accessTokenExpirationMs)))
            .signWith(key)
            .compact();
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public String extractJti(String token) {
        return extractClaims(token).getId();
    }

    public boolean isTokenValid(String token, String expectedUsername) {
        try {
            Claims claims = extractClaims(token);
            return claims.getSubject().equals(expectedUsername) && claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long remainingSeconds(String token) {
        Date exp = extractClaims(token).getExpiration();
        return Math.max(0, (exp.getTime() - System.currentTimeMillis()) / 1000);
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMs / 1000;
    }

    private Claims extractClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
```

**Step 2.8 — `security/UserDetailsServiceImpl.java`**

> **Cập nhật 2026-07-29 — bug nghiêm trọng đã gặp và fix:** bản code đầu tiên thiếu `@Service` trên class này. Hậu quả: Spring không đăng ký được bean `UserDetailsService` tuỳ biến → `AuthenticationManager` (build qua `AuthenticationConfiguration.getAuthenticationManager()` ở `SecurityConfig`) rơi vào vòng lặp cấu hình nội bộ → **`login` luôn ném `StackOverflowError` (500), kể cả đúng email/password**. Verify bằng cách in stack trace thật mới thấy `$Proxy.authenticate()` gọi lại chính nó vô hạn lần. Thêm đúng `@Service` là fix triệt để — nhớ đừng quên annotation này khi tạo `UserDetailsService` tuỳ biến ở project khác.

```java
package com.maaitlunghau.__spring_boot_blueprint.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.UserRepository;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
```

**Step 2.9 — `module/auth/service/AuthService.java`** (bản tối giản — Phase 4 cập nhật lại)

> **Cập nhật 2026-07-29:** message dùng tiếng Anh (`"Email already exists: "`), khớp convention còn lại của code (không phải tiếng Việt như draft dưới). Bản code đầu tiên có bug thật: dòng `orElseThrow` truyền nhầm `request.password()` thay vì `request.email()` vào `ResourceNotFoundException` — nếu exception này lỡ bị trigger, **mật khẩu plaintext sẽ lộ ra trong response JSON** (vì `GlobalExceptionHandler` trả thẳng `ex.getMessage()`). Đã fix đúng thành `request.email()` — draft dưới đây vốn đã đúng, chỉ code thật viết sai lúc đầu.

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maaitlunghau.__spring_boot_blueprint.exception.DuplicateResourceException;
import com.maaitlunghau.__spring_boot_blueprint.exception.ResourceNotFoundException;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.LoginRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RegisterRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.AuthResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.__spring_boot_blueprint.security.JwtService;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        AuthenticationManager authenticationManager,
                        JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already exists: " + request.email());
        }
        User user = new User(request.fullName(), request.email(),
            passwordEncoder.encode(request.password()), Role.USER);
        userRepository.save(user);
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        String accessToken = jwtService.generateAccessToken(user);
        return new AuthResponse(accessToken, jwtService.getAccessTokenExpirationSeconds());
    }
}
```

**Step 2.10 — `module/auth/controller/v1/AuthController.java`** (bản tối giản — Phase 4/6 thêm endpoint)

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.controller.v1;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.LoginRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RegisterRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.AuthResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.message(201, "Registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse tokens = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("Login successfully", tokens));
    }
}
```

> `SecurityConfig` (tạm permit-all) đã được tạo sẵn từ **Phase 1+** (vì CRUD user ở Phase 1+ cũng cần chạy không-auth) — không cần làm lại ở đây. `passwordEncoder()` và `authenticationManager()` bean trong đó chính là 2 bean `AuthService` ở trên đang cần.

**Verify Phase 2:** chạy `./mvnw spring-boot:run` (cần MySQL sống), test bằng curl:

```bash
curl -X POST localhost:8081/api/v1/auth/register -H "Content-Type: application/json" \
  -d '{"fullName":"Alice","email":"alice@example.com","password":"password123"}'
# 201 Registered successfully

curl -X POST localhost:8081/api/v1/auth/register -H "Content-Type: application/json" \
  -d '{"fullName":"Alice","email":"alice@example.com","password":"password123"}'
# 409 Email already exists — verify DuplicateResourceException handler

curl -X POST localhost:8081/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"password123"}'
# 200 kèm accessToken thật

curl -X POST localhost:8081/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"wrong-password"}'
# 401 Invalid email or password — verify BadCredentialsException handler, KHÔNG được 500
```

> Nếu case cuối trả `500` (kèm `StackOverflowError` trong console) — kiểm tra lại `UserDetailsServiceImpl` có annotation `@Service` chưa (xem note ở Step 2.8, bug thật đã gặp đúng chỗ này).

---

### Phase 3 — Wire SecurityConfig + JwtAuthenticationFilter (STATELESS)

**Mục tiêu:** từ giờ endpoint không nằm trong danh sách permit-all sẽ yêu cầu Bearer token hợp lệ.

**Step 3.1 — `security/JwtAuthenticationFilter.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Chạy MỘT LẦN mỗi request: đọc access token từ header Authorization, xác thực, nạp
 * danh tính vào SecurityContext. Không tự trả 401/403 — để EntryPoint/AccessDeniedHandler lo.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            String username = jwtService.extractUsername(token);
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                if (jwtService.isTokenValid(token, userDetails.getUsername())) {
                    var auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
```

**Step 3.2 — `security/CustomAuthenticationEntryPoint.java`** (401 handler)

```java
package com.maaitlunghau.__spring_boot_blueprint.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public CustomAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            objectMapper.writeValueAsString(ApiResponse.message(401, "Yêu cầu đăng nhập")));
    }
}
```

**Step 3.3 — `security/CustomAccessDeniedHandler.java`** (403 handler)

```java
package com.maaitlunghau.__spring_boot_blueprint.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public CustomAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            objectMapper.writeValueAsString(ApiResponse.message(403, "Không có quyền truy cập")));
    }
}
```

**Step 3.4 — `config/CorsConfig.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * API thuần dùng Bearer token (không cookie) -> allowCredentials=false, có thể
 * dùng allowedOriginPatterns rộng hơn project 09 (project 09 dùng cookie nên
 * bắt buộc phải khai origin cụ thể khi allowCredentials=true).
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

**Step 3.5 — Cập nhật lại toàn bộ `config/SecurityConfig.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfConfigurer;

import com.maaitlunghau.__spring_boot_blueprint.security.CustomAccessDeniedHandler;
import com.maaitlunghau.__spring_boot_blueprint.security.CustomAuthenticationEntryPoint;
import com.maaitlunghau.__spring_boot_blueprint.security.JwtAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // bật @PreAuthorize — dùng từ Phase 5
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                           CustomAuthenticationEntryPoint authenticationEntryPoint,
                           CustomAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(CsrfConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

**Verify Phase 3:** gọi `GET /api/v1/users` (đã có từ Phase 1+) mà **không** có header `Authorization` → phải nhận `401` theo đúng shape `ApiResponse` (trước Phase 3 endpoint này permit-all nên sẽ trả `200`; sau Phase 3 phải đổi thành `401` — đây chính là cách xác nhận `SecurityConfig` đã siết đúng). Gọi lại kèm `Authorization: Bearer <access token>` từ `/login` (Phase 2) → phải nhận `200`.

---

### Phase 4 — Refresh token (MySQL/JPA) + rotation/reuse detection

**Mục tiêu:** login trả thêm `refreshToken`; endpoint `/refresh` rotate token, phát hiện reuse.

**Thiết kế bảng `refresh_tokens`** (tạo tự động qua `ddl-auto: update` — Flyway migration chính thức ở Phase 8):

| Column | Type | Ghi chú |
|---|---|---|
| `id`, `created_at`, `updated_at`, `version` | từ `BaseEntity` | `created_at` chính là "issued at", không cần field riêng |
| `user_id` | BIGINT | Chỉ lưu id thô, **không** dùng `@ManyToOne` — tránh lazy-loading không cần thiết, load `User` lại qua `UserRepository` khi thật sự cần (giống cách `AuthService` đã làm) |
| `session_id` | VARCHAR(36) | UUID, khớp với claim `sid` trong access token (Step 4.8). Cũng chính là "family" của chuỗi rotation — 1 login = 1 `session_id` = nhiều token nối tiếp nhau qua rotate, không cần thêm cột `family_id` riêng vì 2 khái niệm này trùng nhau 1-1 trong thiết kế này |
| `token_hash` | VARCHAR(64) UNIQUE | SHA-256 hex của raw token — **không bao giờ lưu raw token**, kể cả trong DB (nếu DB bị đọc trộm, kẻ tấn công vẫn không dùng được) |
| `device_info`, `ip` | VARCHAR | |
| `expires_at` | DATETIME | Idle TTL — **reset mỗi lần rotate** |
| `absolute_expires_at` | DATETIME | Absolute TTL — cố định từ lần login đầu tiên, **không bao giờ reset** khi rotate. Ép user phải đăng nhập lại (nhập mật khẩu thật) sau N ngày dù active liên tục — giới hạn "blast radius" nếu 1 token bị đánh cắp mà chưa bị phát hiện reuse |
| `revoked`, `revoked_at` | BOOLEAN, DATETIME NULL | |
| `revoked_reason` | VARCHAR(30) NULL | `LOGOUT` / `REUSE_DETECTED` / `ROTATED` — phục vụ security audit (vd: cảnh báo khi `REUSE_DETECTED` tăng đột biến) |

**Rotation + reuse detection:** khác với bản Redis (phải đánh dấu `revoked` rồi giữ **grace window 30 giây** vì thao tác đọc-rồi-ghi trên Redis không atomic), bản DB dùng **`SELECT ... FOR UPDATE`** (`@Lock(PESSIMISTIC_WRITE)`) để khoá đúng row đang rotate trong 1 transaction — 2 request `/refresh` dùng chung 1 token sẽ tự xếp hàng, không còn race condition, và **không cần grace window nữa**: request thắng cuộc sẽ revoke + phát token mới; request thua cuộc (dù đến sau 1ms hay 1 ngày) luôn thấy `revoked=true` và bị coi là reuse ngay lập tức.

> **Không có cột `family_id` hay `replaced_by_token_id`:** `session_id` đã đóng đúng vai trò "family" (không có kịch bản nào trong project này khiến 2 khái niệm lệch nhau). Muốn xem chuỗi rotation của 1 session, chỉ cần `WHERE session_id = ? ORDER BY created_at ASC` — thứ tự đã có sẵn từ `created_at` kế thừa `BaseEntity`, không cần thêm FK tự tham chiếu (mà FK đó còn tốn thêm 1 lượt `UPDATE` mỗi lần rotate chỉ để lưu lại thứ suy ra được miễn phí).

**Step 4.1 — `module/auth/entity/RefreshToken.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.entity;

import java.time.LocalDateTime;

import com.maaitlunghau.__spring_boot_blueprint.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "refresh_tokens", indexes = {
    @Index(name = "idx_refresh_tokens_token_hash", columnList = "token_hash", unique = true),
    @Index(name = "idx_refresh_tokens_user_session", columnList = "user_id, session_id"),
    @Index(name = "idx_refresh_tokens_revoked_revoked_at", columnList = "revoked, revoked_at")
})
@Getter
public class RefreshToken extends BaseEntity {

    @Column(nullable = false, name = "user_id")
    private Long userId;

    @Column(nullable = false, name = "session_id", length = 36)
    private String sessionId;

    @Column(nullable = false, name = "token_hash", unique = true, length = 64)
    private String tokenHash;

    @Column(name = "device_info")
    private String deviceInfo;

    @Column(length = 45)
    private String ip;

    /** Idle TTL — reset mỗi lần rotate. */
    @Column(nullable = false, name = "expires_at")
    private LocalDateTime expiresAt;

    /** Absolute TTL — cố định từ lần login đầu, KHÔNG reset khi rotate. */
    @Column(nullable = false, name = "absolute_expires_at")
    private LocalDateTime absoluteExpiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_reason", length = 30)
    @Enumerated(EnumType.STRING)
    private RevokeReason revokedReason;

    protected RefreshToken() {
    }

    public RefreshToken(Long userId, String sessionId, String tokenHash, String deviceInfo, String ip,
                         LocalDateTime expiresAt, LocalDateTime absoluteExpiresAt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.tokenHash = tokenHash;
        this.deviceInfo = deviceInfo;
        this.ip = ip;
        this.expiresAt = expiresAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
    }

    public void revoke(RevokeReason reason) {
        this.revoked = true;
        this.revokedAt = LocalDateTime.now();
        this.revokedReason = reason;
    }

    public boolean isExpired() {
        LocalDateTime now = LocalDateTime.now();
        return expiresAt.isBefore(now) || absoluteExpiresAt.isBefore(now);
    }

    /**
     * Chỉ 3 giá trị thật sự có nơi gọi trong roadmap này — không thêm PASSWORD_CHANGED/
     * ADMIN_REVOKED/SUSPICIOUS_ACTIVITY vì chưa có tính năng đổi mật khẩu/admin panel/
     * anomaly detection nào trong project 10 để sinh ra các lý do đó. Thêm khi tính năng
     * tương ứng thật sự tồn tại.
     */
    public enum RevokeReason {
        LOGOUT,
        REUSE_DETECTED,
        ROTATED
    }
}
```

**Step 4.2 — `module/auth/repository/RefreshTokenRepository.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * PESSIMISTIC_WRITE khoá row lại tới khi transaction kết thúc — đây là điểm khác
     * biệt cốt lõi so với bản Redis: chuỗi đọc-rồi-ghi ở đây atomic thật, không cần
     * grace window để né race condition nữa.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedFalseOrderByCreatedAtDesc(Long userId);

    Optional<RefreshToken> findByUserIdAndSessionIdAndRevokedFalse(Long userId, String sessionId);

    /**
     * 2 nhóm cần dọn, dùng chung 1 query để job cleanup chỉ cần gọi 1 lần:
     * (1) token chưa từng bị revoke nhưng đã qua idle TTL tự nhiên (session bị bỏ quên),
     * (2) token đã revoke — giữ lại {@code revokedRetentionCutoff} (vd 30 ngày) trước khi
     *     xoá, để còn thời gian audit các event `REUSE_DETECTED` trước khi mất dữ liệu.
     * `idx_refresh_tokens_revoked_revoked_at` phục vụ đúng nhánh (2) của query này.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now "
        + "OR (rt.revoked = true AND rt.revokedAt < :revokedRetentionCutoff)")
    void purgeExpiredOrLongRevoked(@Param("now") LocalDateTime now,
                                    @Param("revokedRetentionCutoff") LocalDateTime revokedRetentionCutoff);
}
```

**Step 4.3 — `module/auth/service/RefreshTokenService.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maaitlunghau.__spring_boot_blueprint.exception.InvalidRefreshTokenException;
import com.maaitlunghau.__spring_boot_blueprint.exception.RefreshTokenReuseException;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken.RevokeReason;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.repository.RefreshTokenRepository;

@Service
@Transactional(readOnly = true)
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshTokenExpirationMs;
    private final long refreshTokenAbsoluteExpirationMs;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                                @Value("${app.jwt.refresh-token-expiration}") long refreshTokenExpirationMs,
                                @Value("${app.jwt.refresh-token-absolute-expiration}") long refreshTokenAbsoluteExpirationMs) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
        this.refreshTokenAbsoluteExpirationMs = refreshTokenAbsoluteExpirationMs;
    }

    /** Phát refresh token MỚI cho 1 session mới (login). Trả raw token cho client. */
    @Transactional
    public String issue(Long userId, String sessionId, String deviceInfo, String ip) {
        String rawToken = generateOpaqueToken();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plus(Duration.ofMillis(refreshTokenExpirationMs));
        LocalDateTime absoluteExpiresAt = now.plus(Duration.ofMillis(refreshTokenAbsoluteExpirationMs));
        refreshTokenRepository.save(
            new RefreshToken(userId, sessionId, hash(rawToken), deviceInfo, ip, expiresAt, absoluteExpiresAt));
        return rawToken;
    }

    /**
     * Rotate: verify raw token, phát token mới cùng session, đánh dấu token cũ revoked.
     * Ném RefreshTokenReuseException nếu token đã revoked trước đó bị dùng lại (theft).
     */
    @Transactional
    public RotationResult rotate(String rawOldToken, String deviceInfo, String ip) {
        String oldHash = hash(rawOldToken);
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(oldHash)
            .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token không hợp lệ hoặc đã hết hạn"));

        if (token.isExpired()) {
            throw new InvalidRefreshTokenException("Refresh token đã hết hạn");
        }
        if (token.isRevoked()) {
            revokeAllSessions(token.getUserId(), RevokeReason.REUSE_DETECTED);
            throw new RefreshTokenReuseException(
                "Phát hiện refresh token bị dùng lại. Đã đăng xuất toàn bộ thiết bị.");
        }

        token.revoke(RevokeReason.ROTATED); // không cần gọi save() — dirty checking trong @Transactional lo việc này

        String newRawToken = generateOpaqueToken();
        LocalDateTime expiresAt = LocalDateTime.now().plus(Duration.ofMillis(refreshTokenExpirationMs));
        // absoluteExpiresAt KHÔNG được tính lại — copy nguyên từ token cũ, nếu không sẽ
        // reset y như idle TTL và làm vô nghĩa toàn bộ mục đích của absolute expiration.
        refreshTokenRepository.save(new RefreshToken(
            token.getUserId(), token.getSessionId(), hash(newRawToken), deviceInfo, ip,
            expiresAt, token.getAbsoluteExpiresAt()));

        return new RotationResult(newRawToken, token.getUserId(), token.getSessionId());
    }

    /**
     * Thu hồi 1 session cụ thể (logout hoặc revoke chủ động từ danh sách thiết bị).
     * Khác bản Redis cũ (chỉ xoá sessionId khỏi 1 SET hiển thị, KHÔNG thật sự vô hiệu
     * hoá refresh token bên dưới) — bản này revoke đúng row nên `/refresh` bằng token
     * của session đã bị thu hồi sẽ thật sự thất bại ngay từ lần gọi tiếp theo.
     */
    @Transactional
    public void revokeSession(Long userId, String sessionId, RevokeReason reason) {
        refreshTokenRepository.findByUserIdAndSessionIdAndRevokedFalse(userId, sessionId)
            .ifPresent(t -> t.revoke(reason));
    }

    /** Thu hồi TOÀN BỘ session — hiện chỉ gọi khi phát hiện reuse (Phase 4/6 chưa có tính năng "đăng xuất mọi thiết bị" cho user tự bấm, nhận `reason` để mở rộng sau mà không cần đổi chữ ký method). */
    @Transactional
    public void revokeAllSessions(Long userId, RevokeReason reason) {
        refreshTokenRepository.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(userId)
            .forEach(t -> t.revoke(reason));
    }

    /** Danh sách session đang active — Phase 6 dùng cho `GET /api/v1/auth/sessions`. */
    public List<RefreshToken> listActiveSessions(Long userId) {
        return refreshTokenRepository.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(userId);
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record RotationResult(String newRawRefreshToken, Long userId, String sessionId) {}
}
```

**Step 4.4 — Cập nhật `scheduler/CleanupScheduledTask.java`** — DB không tự hết hạn dữ liệu như Redis TTL, cần job dọn định kỳ để bảng `refresh_tokens` không phình vô hạn

```java
package com.maaitlunghau.__spring_boot_blueprint.scheduler;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.repository.RefreshTokenRepository;

@Component
public class CleanupScheduledTask {

    private final RefreshTokenRepository refreshTokenRepository;

    public CleanupScheduledTask(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    // Giữ token đã revoke thêm 30 ngày trước khi xoá — đủ thời gian audit các event
    // REUSE_DETECTED trước khi mất dữ liệu.
    private static final long REVOKED_RETENTION_DAYS = 30;

    @Scheduled(cron = "0 0 3 * * *") // 3h sáng mỗi ngày
    public void purgeExpiredRefreshTokens() {
        LocalDateTime now = LocalDateTime.now();
        refreshTokenRepository.purgeExpiredOrLongRevoked(now, now.minusDays(REVOKED_RETENTION_DAYS));
    }
}
```

> **Nhớ thêm `@EnableScheduling`** lên `Application.java` (hoặc 1 `@Configuration` bất kỳ) — thiếu annotation này thì `@Scheduled` sẽ **không bao giờ chạy**, không có lỗi/warning gì báo cho biết.

**Step 4.5 — `exception/InvalidRefreshTokenException.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.exception;

public class InvalidRefreshTokenException extends AppException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
```

**`exception/RefreshTokenReuseException.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.exception;

public class RefreshTokenReuseException extends AppException {
    public RefreshTokenReuseException(String message) {
        super(message);
    }
}
```

**Step 4.6 — Cập nhật `exception/GlobalExceptionHandler.java`** — thêm 2 handler (chèn vào trước `handleGeneral`)

```java
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidRefresh(InvalidRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.message(401, ex.getMessage()));
    }

    @ExceptionHandler(RefreshTokenReuseException.class)
    public ResponseEntity<ApiResponse<Void>> handleReuse(RefreshTokenReuseException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.message(401, ex.getMessage()));
    }
```

**Step 4.7 — `util/RequestUtils.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.util;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestUtils {

    private RequestUtils() {}

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua != null ? ua : "unknown";
    }
}
```

**Step 4.8 — Cập nhật lại toàn bộ `security/JwtService.java`** — thêm claim `sid` (sessionId) để `logout`/session-management (Phase 6) tự biết session nào mà không cần client gửi thêm gì

```java
package com.maaitlunghau.__spring_boot_blueprint.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenExpirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.access-token-expiration}") long accessTokenExpirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    public String generateAccessToken(User user, String sessionId) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(user.getEmail())
            .id(UUID.randomUUID().toString())
            .claim("role", user.getRole().name())
            .claim("sid", sessionId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(accessTokenExpirationMs)))
            .signWith(key)
            .compact();
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public String extractJti(String token) {
        return extractClaims(token).getId();
    }

    public String extractSessionId(String token) {
        return extractClaims(token).get("sid", String.class);
    }

    public boolean isTokenValid(String token, String expectedUsername) {
        try {
            Claims claims = extractClaims(token);
            return claims.getSubject().equals(expectedUsername) && claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long remainingSeconds(String token) {
        Date exp = extractClaims(token).getExpiration();
        return Math.max(0, (exp.getTime() - System.currentTimeMillis()) / 1000);
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMs / 1000;
    }

    private Claims extractClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
```

**Step 4.9 — Cập nhật lại toàn bộ `module/auth/dto/response/AuthResponse.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response;

public record AuthResponse(String accessToken, String refreshToken, long expiresIn) {}
```

**Step 4.10 — `module/auth/dto/request/RefreshRequest.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank(message = "refreshToken là bắt buộc") String refreshToken) {}
```

**Step 4.11 — Cập nhật lại toàn bộ `module/auth/service/AuthService.java`** — không đổi logic so với bản Redis, vì `RefreshTokenService` vẫn giữ nguyên chữ ký public method (`issue`/`rotate`)

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.service;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maaitlunghau.__spring_boot_blueprint.exception.DuplicateResourceException;
import com.maaitlunghau.__spring_boot_blueprint.exception.ResourceNotFoundException;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.LoginRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RegisterRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.AuthResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.RefreshTokenService.RotationResult;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.__spring_boot_blueprint.security.JwtService;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        AuthenticationManager authenticationManager,
                        JwtService jwtService,
                        RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email đã tồn tại: " + request.email());
        }
        User user = new User(request.fullName(), request.email(),
            passwordEncoder.encode(request.password()), Role.USER);
        userRepository.save(user);
    }

    public AuthResponse login(LoginRequest request, String deviceInfo, String ip) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtService.generateAccessToken(user, sessionId);
        String refreshToken = refreshTokenService.issue(user.getId(), sessionId, deviceInfo, ip);

        return new AuthResponse(accessToken, refreshToken, jwtService.getAccessTokenExpirationSeconds());
    }

    public AuthResponse refresh(String rawRefreshToken, String deviceInfo, String ip) {
        RotationResult rotation = refreshTokenService.rotate(rawRefreshToken, deviceInfo, ip);

        User user = userRepository.findById(rotation.userId())
            .orElseThrow(() -> new ResourceNotFoundException("User", rotation.userId()));

        String accessToken = jwtService.generateAccessToken(user, rotation.sessionId());
        return new AuthResponse(accessToken, rotation.newRawRefreshToken(), jwtService.getAccessTokenExpirationSeconds());
    }
}
```

**Step 4.12 — Cập nhật lại toàn bộ `module/auth/controller/v1/AuthController.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.controller.v1;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.LoginRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RefreshRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RegisterRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.AuthResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.AuthService;
import com.maaitlunghau.__spring_boot_blueprint.util.RequestUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.message(201, "Đăng ký thành công"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                             HttpServletRequest servletRequest) {
        AuthResponse tokens = authService.login(request,
            RequestUtils.userAgent(servletRequest), RequestUtils.clientIp(servletRequest));
        return ResponseEntity.ok(ApiResponse.ok("Đăng nhập thành công", tokens));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest request,
                                                               HttpServletRequest servletRequest) {
        AuthResponse tokens = authService.refresh(request.refreshToken(),
            RequestUtils.userAgent(servletRequest), RequestUtils.clientIp(servletRequest));
        return ResponseEntity.ok(ApiResponse.ok("Cấp access token mới", tokens));
    }
}
```

**Verify Phase 4:** login → lấy `refreshToken` → gọi `/refresh` lần 1 (thành công, nhận token mới; kiểm tra bảng `refresh_tokens` thấy row cũ `revoked=true`, row mới `revoked=false`) → gọi `/refresh` lần 2 **với token cũ đã dùng** (bất kỳ lúc nào sau đó, không còn giới hạn khung giờ như bản Redis) → phải nhận lỗi `RefreshTokenReuseException` (401) và toàn bộ session của user bị revoke.

---

### Phase 5 — Thêm phân quyền (`@PreAuthorize`) lên `user` CRUD đã có từ Phase 1+

**Mục tiêu:** CRUD user đã chạy được (không auth) từ Phase 1+ — phase này **không xây lại từ đầu**, chỉ bổ sung phân quyền role/ownership giờ đã có JWT filter (Phase 3) để dùng.

> **Cập nhật 2026-07-29:** `UpdateProfileRequest`/`UpdateUserRoleRequest`, `UserService`/`UserServiceImpl` (`updateProfile()`/`updateRole()`), và 2 endpoint `PATCH /{id}/profile`/`PUT /{id}/role` **đã được làm sẵn từ Phase 1+** (xem Step 1+.6/1+.9/1+.10/1+.11) — không phải đợi tới Phase 5 mới tách. Lý do dời sớm: tách theo đúng ranh giới nghiệp vụ (hồ sơ vs quyền hạn) ngay từ đầu rõ ràng hơn là viết 1 DTO gộp rồi xoá đi làm lại. Phase 5 giờ **chỉ còn duy nhất 1 việc**: gắn `@PreAuthorize` lên các endpoint đã có sẵn + thêm 2 endpoint `/me` (chính chủ, dùng `@AuthenticationPrincipal` — có được từ Phase 3). Không cần đụng lại DTO/service.

**Step 5.1 — Cập nhật lại toàn bộ `module/user/controller/v1/UserController.java`** — thêm `@PreAuthorize`, thêm `/me` (chính chủ, dùng `@AuthenticationPrincipal` — có được từ Phase 3)

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.controller.v1;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateUserRoleRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.__spring_boot_blueprint.module.user.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Role role,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(userService.search(keyword, role, pageable)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(201, "Created successfully", created));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getById(user.getId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getById(id)));
    }

    @PatchMapping("/{id}/profile")
    @PreAuthorize("#id == authentication.principal.id or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(@PathVariable Long id,
                                                                     @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Updated profile successfully", userService.updateProfile(id, request)));
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> updateRole(@PathVariable Long id,
                                                                  @Valid @RequestBody UpdateUserRoleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Updated role successfully", userService.updateRole(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.ok(ApiResponse.message(200, "Deleted successfully"));
    }
}
```

> `@AuthenticationPrincipal User user` inject thẳng entity `User` vì nó implements `UserDetails` và `JwtAuthenticationFilter` đã set nó làm principal (Step 3.1). `PATCH /{id}/profile` và `PUT /{id}/role` **giữ nguyên path** từ Phase 1+ — chỉ thêm `@PreAuthorize`: profile cho phép chính chủ (`#id == authentication.principal.id`) hoặc ADMIN, role chỉ ADMIN. `GET /me` là endpoint mới, tiện cho client tự lấy hồ sơ mà không cần biết trước `id` của chính mình.

**Verify Phase 5:** đăng nhập bằng 1 user role `USER` thường, gọi `GET /api/v1/users` → phải nhận `403`. Đăng nhập bằng user role `ADMIN` (tạo từ Phase 1+) → gọi lại → `200`.

---

### Phase 6 — Logout/blacklist, rate limit login, multi-device session

**Mục tiêu:** hoàn thiện vòng đời token — logout thật sự vô hiệu hoá access token đang dùng, chống brute-force, quản lý thiết bị.

> **Redis lần đầu xuất hiện ở đây, không phải Phase 4:** access-token blacklist và rate-limit counter là dữ liệu ngắn hạn, chấp nhận mất được — đúng chỗ Redis mạnh nhất (xem lại quyết định lưu trữ ở Mục 1). Refresh token (Phase 4) đã dùng MySQL nên không cần Redis từ trước.

**Step 6.1 — Thêm dependency Redis vào `pom.xml`**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**Thêm cấu hình Redis vào `application.yml`**

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
```

**`config/RedisConfig.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Spring Boot đã tự auto-config StringRedisTemplate khi có starter-data-redis trên
 * classpath — khai báo tường minh ở đây để dễ tuỳ biến serializer sau này nếu cần.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
```

**Step 6.2 — `module/auth/service/TokenBlacklistService.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.service;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void blacklist(String jti, long remainingSeconds) {
        if (remainingSeconds <= 0) return; // token đã hết hạn tự nhiên, không cần blacklist
        redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(remainingSeconds));
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }
}
```

**Step 6.3 — Cập nhật lại toàn bộ `security/JwtAuthenticationFilter.java`** — check blacklist trước khi set context

```java
package com.maaitlunghau.__spring_boot_blueprint.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.TokenBlacklistService;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(JwtService jwtService,
                                    UserDetailsService userDetailsService,
                                    TokenBlacklistService tokenBlacklistService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            String username = jwtService.extractUsername(token);
            String jti = jwtService.extractJti(token);

            if (username != null
                    && SecurityContextHolder.getContext().getAuthentication() == null
                    && !tokenBlacklistService.isBlacklisted(jti)) {

                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                if (jwtService.isTokenValid(token, userDetails.getUsername())) {
                    var auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
```

**Step 6.4 — Cập nhật lại toàn bộ `module/auth/service/AuthService.java`** — thêm `logout()`

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.service;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maaitlunghau.__spring_boot_blueprint.exception.DuplicateResourceException;
import com.maaitlunghau.__spring_boot_blueprint.exception.ResourceNotFoundException;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.LoginRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RegisterRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.AuthResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken.RevokeReason;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.RefreshTokenService.RotationResult;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.__spring_boot_blueprint.security.JwtService;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        AuthenticationManager authenticationManager,
                        JwtService jwtService,
                        RefreshTokenService refreshTokenService,
                        TokenBlacklistService tokenBlacklistService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email đã tồn tại: " + request.email());
        }
        User user = new User(request.fullName(), request.email(),
            passwordEncoder.encode(request.password()), Role.USER);
        userRepository.save(user);
    }

    public AuthResponse login(LoginRequest request, String deviceInfo, String ip) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtService.generateAccessToken(user, sessionId);
        String refreshToken = refreshTokenService.issue(user.getId(), sessionId, deviceInfo, ip);

        return new AuthResponse(accessToken, refreshToken, jwtService.getAccessTokenExpirationSeconds());
    }

    public AuthResponse refresh(String rawRefreshToken, String deviceInfo, String ip) {
        RotationResult rotation = refreshTokenService.rotate(rawRefreshToken, deviceInfo, ip);

        User user = userRepository.findById(rotation.userId())
            .orElseThrow(() -> new ResourceNotFoundException("User", rotation.userId()));

        String accessToken = jwtService.generateAccessToken(user, rotation.sessionId());
        return new AuthResponse(accessToken, rotation.newRawRefreshToken(), jwtService.getAccessTokenExpirationSeconds());
    }

    @Transactional
    public void logout(String accessToken) {
        String jti = jwtService.extractJti(accessToken);
        String sessionId = jwtService.extractSessionId(accessToken);
        String username = jwtService.extractUsername(accessToken);
        long remaining = jwtService.remainingSeconds(accessToken);

        tokenBlacklistService.blacklist(jti, remaining);

        User user = userRepository.findByEmail(username)
            .orElseThrow(() -> new ResourceNotFoundException("User", username));
        refreshTokenService.revokeSession(user.getId(), sessionId, RevokeReason.LOGOUT);
    }
}
```

**Step 6.5 — Cập nhật lại toàn bộ `module/auth/controller/v1/AuthController.java`** — thêm `/logout`

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.controller.v1;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.LoginRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RefreshRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RegisterRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.AuthResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.AuthService;
import com.maaitlunghau.__spring_boot_blueprint.util.RequestUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.message(201, "Đăng ký thành công"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                             HttpServletRequest servletRequest) {
        AuthResponse tokens = authService.login(request,
            RequestUtils.userAgent(servletRequest), RequestUtils.clientIp(servletRequest));
        return ResponseEntity.ok(ApiResponse.ok("Đăng nhập thành công", tokens));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest request,
                                                               HttpServletRequest servletRequest) {
        AuthResponse tokens = authService.refresh(request.refreshToken(),
            RequestUtils.userAgent(servletRequest), RequestUtils.clientIp(servletRequest));
        return ResponseEntity.ok(ApiResponse.ok("Cấp access token mới", tokens));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        authService.logout(token);
        return ResponseEntity.ok(ApiResponse.message(200, "Đăng xuất thành công"));
    }
}
```

**Step 6.6 — `module/auth/controller/v1/SessionController.java`** (liệt kê/thu hồi thiết bị)

> **Nâng cấp so với bản Redis:** trước đây chỉ có `Set<String>` sessionId (không biết thiết bị nào, IP nào, đăng nhập lúc nào) vì Redis SET chỉ lưu được ID thô. Giờ có bảng `refresh_tokens` thật, `listActiveSessions` trả về cả entity — đủ dữ liệu để hiển thị "đang đăng nhập trên: Chrome/Windows, IP 1.2.3.4, từ 10:30 hôm nay" mà không cần thêm chỗ lưu nào khác.

**`module/auth/dto/response/SessionResponse.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response;

import java.time.LocalDateTime;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken;

public record SessionResponse(String sessionId, String deviceInfo, String ip, LocalDateTime createdAt) {
    public static SessionResponse from(RefreshToken token) {
        return new SessionResponse(token.getSessionId(), token.getDeviceInfo(), token.getIp(), token.getCreatedAt());
    }
}
```

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.controller.v1;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.SessionResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken.RevokeReason;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.RefreshTokenService;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

@RestController
@RequestMapping("/api/v1/auth/sessions")
public class SessionController {

    private final RefreshTokenService refreshTokenService;

    public SessionController(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SessionResponse>>> list(@AuthenticationPrincipal User user) {
        List<SessionResponse> sessions = refreshTokenService.listActiveSessions(user.getId()).stream()
            .map(SessionResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.ok(sessions));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> revoke(@AuthenticationPrincipal User user,
                                                      @PathVariable String sessionId) {
        refreshTokenService.revokeSession(user.getId(), sessionId, RevokeReason.LOGOUT);
        return ResponseEntity.ok(ApiResponse.message(200, "Đã thu hồi phiên đăng nhập"));
    }
}
```

**Step 6.7 — `filter/RateLimitFilter.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.filter;

import java.io.IOException;
import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.util.RequestUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Giới hạn số lần gọi /api/v1/auth/login theo IP — chống brute-force. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        if (!LOGIN_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = "auth:ratelimit:login:" + RequestUtils.clientIp(request);
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
        if (attempts != null && attempts > MAX_ATTEMPTS) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.message(429, "Quá nhiều lần thử đăng nhập, vui lòng thử lại sau")));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
```

**Step 6.8 — Cập nhật lại toàn bộ `config/SecurityConfig.java`** — wire `RateLimitFilter` trước `JwtAuthenticationFilter`

```java
package com.maaitlunghau.__spring_boot_blueprint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfConfigurer;

import com.maaitlunghau.__spring_boot_blueprint.filter.RateLimitFilter;
import com.maaitlunghau.__spring_boot_blueprint.security.CustomAccessDeniedHandler;
import com.maaitlunghau.__spring_boot_blueprint.security.CustomAuthenticationEntryPoint;
import com.maaitlunghau.__spring_boot_blueprint.security.JwtAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                           RateLimitFilter rateLimitFilter,
                           CustomAuthenticationEntryPoint authenticationEntryPoint,
                           CustomAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(CsrfConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

> Lưu ý endpoint `/api/v1/auth/sessions/**` (Step 6.6) đang KHÔNG nằm trong `permitAll` (chỉ `/api/v1/auth/**` được permit toàn bộ) — thực tế path này match `/api/v1/auth/**` nên vẫn permitAll! Cần sửa lại rule để `/api/v1/auth/sessions/**` yêu cầu authenticated trong khi `/api/v1/auth/{register,login,refresh}` thì permitAll. Cách làm: liệt kê rõ từng path thay vì dùng wildcard `/api/v1/auth/**` — xem lại `authorizeHttpRequests` ở trên và tự sửa thành:
> ```java
> .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
> .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
> .anyRequest().authenticated()
> ```
> Đây là lỗi cố ý để lại trong doc để bạn tự nhận ra khi test — matcher càng rộng càng dễ vô tình permit nhầm endpoint nhạy cảm; luôn liệt kê tường minh thay vì wildcard cho path prefix chứa cả public lẫn protected endpoint.

**Verify Phase 6:** gọi `/login` sai mật khẩu 6 lần liên tiếp trong 60s → lần thứ 6 phải nhận `429`. Logout xong, dùng lại access token cũ gọi `/api/v1/users/me` → phải nhận `401`.

---

### Phase 7 — Test

**Mục tiêu:** phủ 3 tầng test pyramid: unit (Mockito, không load context) → slice (`@WebMvcTest`/`@DataJpaTest`) → integration (`@SpringBootTest` + Testcontainers, Phase 8). Dưới đây là ví dụ đầy đủ cho `auth`; áp dụng đúng pattern này cho `user`.

**Step 7.1 — Unit test: `module/auth/service/AuthServiceTest.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.maaitlunghau.__spring_boot_blueprint.exception.DuplicateResourceException;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.LoginRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RegisterRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.AuthResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.__spring_boot_blueprint.security.JwtService;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthenticationManager authenticationManager;
    @Mock JwtService jwtService;
    @Mock RefreshTokenService refreshTokenService;
    @Mock TokenBlacklistService tokenBlacklistService;

    @InjectMocks AuthService authService;

    @Test
    void should_throw_when_email_already_exists() {
        RegisterRequest request = new RegisterRequest("Alice", "alice@example.com", "password123");
        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
            .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void should_return_tokens_when_login_succeeds() {
        LoginRequest request = new LoginRequest("alice@example.com", "password123");
        User user = new User("Alice", "alice@example.com", "hashed", Role.USER);
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(any(), any())).thenReturn("access-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(900L);
        when(refreshTokenService.issue(any(), any(), any(), any())).thenReturn("refresh-token");

        AuthResponse result = authService.login(request, "test-agent", "127.0.0.1");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
    }
}
```

**Step 7.2 — Slice test (`@WebMvcTest`): `module/auth/controller/v1/AuthControllerTest.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.controller.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.AuthResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.AuthService;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AuthService authService;

    @Test
    void should_return_400_when_email_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Alice","email":"not-an-email","password":"123456"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_200_when_login_succeeds() throws Exception {
        when(authService.login(any(), any(), any()))
            .thenReturn(new AuthResponse("access", "refresh", 900L));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"alice@example.com","password":"password123"}
                    """))
            .andExpect(status().isOk());
    }
}
```

> Dùng `@MockitoBean` (package `org.springframework.test.context.bean.override.mockito`), **không** dùng `@MockBean` cũ — annotation đó đã deprecated từ Spring Boot 3.4 trở đi.

**Step 7.3 — Repository test (`@DataJpaTest`): `module/user/repository/UserRepositoryTest.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.module.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

@DataJpaTest
class UserRepositoryTest {

    @Autowired UserRepository userRepository;

    @Test
    void should_find_user_by_email() {
        userRepository.save(new User("Alice", "alice@example.com", "hashed", Role.USER));

        var found = userRepository.findByEmail("alice@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getFullName()).isEqualTo("Alice");
    }
}
```

> Mặc định `@DataJpaTest` thay DB thật bằng H2 in-memory (khác dialect MySQL). Chạy được ngay không cần setup gì, nhưng muốn test đúng dialect MySQL thật thì cần cấu hình Testcontainers ở Phase 8 và thêm `@AutoConfigureTestDatabase(replace = Replace.NONE)`.

**Step 7.4 — Repository test (`@DataJpaTest`): `module/auth/repository/RefreshTokenRepositoryTest.java`** — bắt buộc thêm vì `RefreshToken` giờ là JPA entity thật (không có ở bản Redis)

```java
package com.maaitlunghau.__spring_boot_blueprint.module.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken;

@DataJpaTest
class RefreshTokenRepositoryTest {

    @Autowired RefreshTokenRepository refreshTokenRepository;

    @Test
    void should_find_token_by_hash_for_update() {
        refreshTokenRepository.save(new RefreshToken(1L, "session-1", "hash-abc", "chrome", "127.0.0.1",
            LocalDateTime.now().plusDays(7), LocalDateTime.now().plusDays(30)));

        var found = refreshTokenRepository.findByTokenHashForUpdate("hash-abc");

        assertThat(found).isPresent();
        assertThat(found.get().getSessionId()).isEqualTo("session-1");
    }

    @Test
    void should_purge_expired_or_long_revoked_tokens() {
        refreshTokenRepository.save(new RefreshToken(1L, "session-2", "hash-expired", "chrome", "127.0.0.1",
            LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(29)));

        LocalDateTime now = LocalDateTime.now();
        refreshTokenRepository.purgeExpiredOrLongRevoked(now, now.minusDays(30));

        assertThat(refreshTokenRepository.findByTokenHashForUpdate("hash-expired")).isEmpty();
    }
}
```

**Verify Phase 7:** `./mvnw test` — cả 4 test trên phải pass. Viết thêm `UserServiceTest`, `UserControllerTest` theo đúng 2 pattern unit/slice ở trên, và `RefreshTokenServiceTest` (mock `RefreshTokenRepository`, verify rotate() phát token mới + verify reuse ném đúng `RefreshTokenReuseException` khi token đã `revoked`).

---

### Phase 8 — Polish: Flyway, OpenAPI, Docker/CI

**Mục tiêu:** rời khỏi `ddl-auto: update`, có docs tự sinh, container hoá được, CI chạy test tự động.

**Step 8.1 — Thêm dependency Flyway + Testcontainers + springdoc vào `pom.xml`**

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.5</version>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
```

> `spring-boot-starter-parent` đã quản lý version Testcontainers qua BOM — không cần khai version cho 2 dependency Testcontainers. `springdoc` chưa nằm trong BOM của Spring Boot nên phải tự ghi version.

**Step 8.2 — `src/main/resources/db/migration/V1__create_users_table.sql`**

```sql
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_users_email ON users (email);
```

**`src/main/resources/db/migration/V2__create_refresh_tokens_table.sql`** — bảng mới do Phase 4 chuyển refresh token từ Redis sang MySQL

```sql
CREATE TABLE refresh_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    device_info VARCHAR(255),
    ip VARCHAR(45),
    expires_at DATETIME NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
```

> `token_hash` đã có `UNIQUE` nên tự động có index — không cần thêm `CREATE INDEX` riêng cho cột này.

**Step 8.3 — Cập nhật `application.yml`** — chuyển `ddl-auto` sang `validate`, bật Flyway

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # trước đó là update — Flyway giờ chịu trách nhiệm schema
  flyway:
    enabled: true
    locations: classpath:db/migration
```

**Step 8.4 — `config/OpenApiConfig.java`**

```java
package com.maaitlunghau.__spring_boot_blueprint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        String schemeName = "bearerAuth";
        return new OpenAPI()
            .info(new Info().title("Spring Boot Blueprint API").version("v1"))
            .addSecurityItem(new SecurityRequirement().addList(schemeName))
            .components(new Components().addSecuritySchemes(schemeName,
                new SecurityScheme()
                    .name(schemeName)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")));
    }
}
```

Truy cập Swagger UI tại `http://localhost:8081/swagger-ui.html` sau khi thêm dependency + config này.

**Step 8.5 — `Dockerfile`**

```dockerfile
# Stage 1: build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Step 8.6 — `docker-compose.yml`**

```yaml
services:
  mysql:
    image: mysql:8
    container_name: blueprint-mysql
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: 112233
      MYSQL_DATABASE: spring-boot-blueprint
    volumes:
      - blueprint_mysql:/var/lib/mysql

  redis:
    image: redis:7
    container_name: blueprint-redis
    ports:
      - "6379:6379"

volumes:
  blueprint_mysql:
```

**Step 8.7 — `.env.example`**

```
MYSQL_HOST=localhost
REDIS_HOST=localhost
JWT_SECRET=ChangeThisToARandomSecretAtLeast32BytesLongForHS256!!
```

**Step 8.8 — `.github/workflows/ci.yml`**

```yaml
name: CI

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: projects/10-spring-boot-blueprint
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - run: ./mvnw verify -B
```

**Verify Phase 8:** `docker compose up -d`, `./mvnw spring-boot:run` (nay dùng Flyway thay vì `ddl-auto`) — app phải start sạch, bảng `users` được tạo qua migration `V1`, không phải Hibernate tự sinh.

---

## 7. Danh sách endpoint

| Method | Path | Auth (từ Phase nào) | Có từ Phase |
|---|---|---|---|
| GET | `/api/v1/users` | public → ADMIN (Phase 5) | 1+ |
| POST | `/api/v1/users` | public → ADMIN (Phase 5) | 1+ |
| GET | `/api/v1/users/{id}` | public → ADMIN (Phase 5) | 1+ |
| PATCH | `/api/v1/users/{id}` | public — **bị xoá ở Phase 5**, thay bằng 2 dòng dưới | 1+ → xoá ở 5 |
| DELETE | `/api/v1/users/{id}` | public → ADMIN (Phase 5) | 1+ |
| POST | `/api/v1/auth/register` | public | 2 |
| POST | `/api/v1/auth/login` | public | 2 (refresh token từ Phase 4) |
| POST | `/api/v1/auth/refresh` | refresh token | 4 |
| GET | `/api/v1/users/me` | chính chủ | 5 |
| PATCH | `/api/v1/users/me` | chính chủ | 5 |
| PATCH | `/api/v1/users/{id}/role` | ADMIN | 5 |
| POST | `/api/v1/auth/logout` | access token | 6 |
| GET | `/api/v1/auth/sessions` | access token | 6 |
| DELETE | `/api/v1/auth/sessions/{sessionId}` | access token | 6 |

---

## 8. Quyết định đã chốt

| Quyết định | Chốt | Vì sao |
|---|---|---|
| Access token truyền qua đâu? | **Cập nhật 2026-08-01:** cả 2 — httpOnly cookie (`access_token`/`refresh_token`) **và** `Authorization: Bearer` header, song song | Ban đầu chọn thuần Bearer vì nghĩ API không có web app cùng-origin. Nhưng project 10 sẽ có 1 web app tương tự project 09 → cần cookie cho web app đó, **đồng thời vẫn giữ Bearer** để Postman/mobile/service-to-service không bị gãy. `JwtAuthenticationFilter.resolveToken()` đọc cookie trước, fallback header. Đánh đổi phải chấp nhận: `AuthResponse` vẫn trả token trong JSON body (cho client Bearer dùng) → nếu web app dính XSS, script độc hại vẫn đọc được token qua response JSON dù cookie có `httpOnly` — tức `httpOnly` chỉ bảo vệ được 1 phần khi bắt buộc hỗ trợ song song 2 kiểu client, không triệt để như 1 API thuần-cookie |
| CSRF: bật hay tắt? | **Cập nhật 2026-08-01:** bật lại (`CookieCsrfTokenRepository` + `CsrfCookieFilter`, giống project 09) | Vì giờ đã dùng cookie cho phần web app — cookie tự động gửi kèm request nghĩa là phải chống CSRF, nếu không sẽ lộ lỗ hổng thật. Nhưng **không áp dụng CSRF cho request có Bearer header hợp lệ** (`CSRF_REQUIRED_MATCHER` trong `SecurityConfig`) — Bearer token không bao giờ bị trình duyệt tự động gắn vào request giả mạo cross-site nên miễn nhiễm CSRF tự nhiên, bắt CSRF token với client Postman/mobile chỉ làm gãy API vô cớ. Miễn CSRF tường minh cho `register/login/refresh/logout` (liệt kê rõ từng path, **không** dùng wildcard `/api/v1/auth/**` — tránh lặp lại đúng bẫy wildcard đã né ở Step 6.8, vì `SessionController` cũng nằm dưới `/api/v1/auth/sessions` và **phải** được CSRF bảo vệ) |
| Refresh token: JWT hay opaque? | Opaque random string, hash SHA-256 trước khi lưu MySQL | Revoke tức thời được, không thể forge/decode như JWT; hash để dù DB bị đọc trộm cũng không dùng được token |
| Refresh token: lưu ở đâu? | MySQL (JPA Entity `RefreshToken`) — **không** phải Redis | Pattern chuẩn/phổ biến nhất cho refresh token: bền, không mất khi Redis restart/evict (mất = force-logout hàng loạt). Redis chỉ giữ access-token blacklist + rate limit (Phase 6) — dữ liệu ngắn hạn, chấp nhận mất được |
| 1 session hay multi-device? | Multi-device (bảng `refresh_tokens`, query theo `user_id`) | Điểm khác biệt "nâng cao" so với project 09; đồng thời tận dụng được để trả `deviceInfo`/`ip` thật cho `GET /sessions` (Phase 6) — thứ Redis SET không lưu được |
| Sign JWT bằng gì? | HS256 | Đủ cho monolith 1 service; RS256 chỉ cần khi nhiều service verify độc lập |
| Rotation + reuse detection | Đánh dấu `revoked` trong 1 transaction có `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) | DB cho atomicity thật — không cần "grace window" như cách né race condition của Redis (đọc-rồi-ghi 2 bước không atomic) |
| Idle TTL hay thêm absolute TTL? | Cả 2: `expires_at` (idle, reset mỗi lần rotate) **và** `absolute_expires_at` (cố định từ lần login đầu, không bao giờ reset) | Chỉ idle TTL thì user active liên tục có thể không bao giờ bị buộc login lại — thiếu 1 trần cứng giới hạn "blast radius" nếu token bị lộ mà chưa phát hiện reuse (chuẩn OWASP/NIST session management) |
| Có lưu `revoked_reason` không? | Có, enum rút gọn còn đúng 3 giá trị: `LOGOUT`, `REUSE_DETECTED`, `ROTATED` | Giá trị cho security audit gần như miễn phí (1 cột). Không thêm `PASSWORD_CHANGED`/`ADMIN_REVOKED`/`SUSPICIOUS_ACTIVITY`/`LOGOUT_ALL` vì project 10 **chưa có** tính năng đổi mật khẩu, admin panel, anomaly detection, hay "đăng xuất mọi thiết bị" tương ứng — thêm khi tính năng đó thật sự tồn tại |
| Có tách `family_id` riêng khỏi `session_id` không? | Không — dùng chung `session_id` | 2 khái niệm có lifecycle giống hệt nhau trong thiết kế này (tạo mới ở login, giữ nguyên qua các lần rotate) — tách ra là double bookkeeping không có lợi ích hành vi nào ở quy mô project này |
| Có thêm `replaced_by_token_id` (FK tự tham chiếu) không? | Không | Suy ra được miễn phí bằng `WHERE session_id = ? ORDER BY created_at ASC` (thứ tự có sẵn từ `BaseEntity`) — FK riêng chỉ tốn thêm 1 lượt `UPDATE` mỗi lần rotate cho thông tin đã có sẵn |
| DTO update profile vs update role | 2 DTO/2 endpoint riêng (`UpdateProfileRequest` vs `UpdateUserRoleRequest`) | Không cho user tự nâng quyền qua endpoint tự sửa profile |

---

## 9. Checklist thực thi

- [x] Phase 1 — `BaseEntity`, `Role`, `User`, `UserRepository`
- [x] Phase 1+ — `SecurityConfig` tạm permit-all, `ApiResponse` (+factory `of`), `PageResponse`, `UserResponse`, `CreateUserRequest`/`UpdateProfileRequest`/`UpdateUserRoleRequest` (đã tách DTO ngay từ Phase này, không đợi Phase 5), `UserSpecifications`, `UserService`/`UserServiceImpl`/`UserController` (CRUD + `PATCH /{id}/profile` (partial update) + `PUT /{id}/role`, chưa auth), `GlobalExceptionHandler` + `AppException`/`BadRequestException`/`DuplicateResourceException`/`ResourceNotFoundException`
- [x] Phase 2 — JJWT dependency, `JwtService`, `UserDetailsServiceImpl`, `AuthService` (register/login), `AuthController`, `GlobalExceptionHandler` + `BadCredentialsException` (401)
- [x] Phase 3 — `JwtAuthenticationFilter`, entry point/access-denied handler, `CorsConfig`, cập nhật `SecurityConfig` sang STATELESS
- [x] Phase 4 — `RefreshToken` entity, `RefreshTokenRepository` (pessimistic lock), `RefreshTokenService` (rotation + reuse detection, MySQL), `CleanupScheduledTask` (purge hết hạn) + `@EnableScheduling`, cập nhật `JwtService`/`AuthService`/`AuthController`
- [x] Phase 5 — `@PreAuthorize` lên `UserController` + `GET /me` + `@EnableMethodSecurity` (thiếu ở lần code đầu, tự phát hiện khi review lại — không có thì mọi `@PreAuthorize` vô tác dụng)
- [x] Phase 6 — Redis dependency, `TokenBlacklistService`, cập nhật `JwtAuthenticationFilter`/`AuthService`/`AuthController`, `SessionController` (+ `SessionResponse`), `RateLimitFilter` (mở rộng thêm `/register`+`/refresh`, không chỉ `/login`) — bẫy wildcard CORS/permitAll ở Step 6.8 đã tránh đúng
- [x] Bổ sung ngoài roadmap gốc (2026-08-01) — **auth qua cookie song song với Bearer**, vì project 10 sẽ có web app tương tự project 09: `CookieUtils` (set/read/clear `access_token`+`refresh_token` cookie), `JwtAuthenticationFilter` đọc cookie trước/header sau, `CorsConfig` đổi origin cụ thể + `allowCredentials(true)`, bật lại CSRF (`CookieCsrfTokenRepository` + `CsrfCookieFilter`) nhưng **chỉ áp dụng cho request không mang Bearer header** (`SecurityConfig.CSRF_REQUIRED_MATCHER`) để không làm gãy client Postman/mobile. Xem thêm 2 dòng đầu mục 8 cho chi tiết + đánh đổi (token vẫn lộ qua JSON body cho client Bearer dùng)
- [ ] Phase 7 — `AuthServiceTest`, `AuthControllerTest`, `UserRepositoryTest`, `RefreshTokenRepositoryTest` + viết thêm test tương tự cho `user` và `RefreshTokenServiceTest`. **Ưu tiên thêm 1 test `@DataJpaTest`/`@SpringBootTest` thật cho `RefreshTokenServiceImpl.rotate()`** — bug thiếu `@Transactional` (đã fix) là loại lỗi mock không bắt được, chỉ lộ ra khi chạy với Hibernate session thật
- [ ] Phase 8 — Flyway migration, `ddl-auto: validate`, `OpenApiConfig`, Testcontainers cho integration test. `Dockerfile`/`docker-compose.yml`/`.env.example`/CI đã làm xong sớm hơn dự kiến (ở `.github/workflows/blueprint-{ci,cd}.yml` tại root repo, không phải trong thư mục project — GitHub Actions chỉ đọc `.github/workflows/` ở root)

Checklist hạ tầng/root file (trước khi có nội dung phase 8) xem thêm ở `projects/10-spring-boot-blueprint/README.md`.

