# Java Spring Boot Fundamentals

Dự án học và luyện tập **Java Spring Framework + Spring Boot** theo kiểu mono-repo — nhiều sub-project Spring Boot độc lập trong cùng một repo, mỗi sub-project tập trung vào một chủ đề.

## Mục tiêu học

- Spring Core: IoC Container, Dependency Injection, Bean lifecycle, AOP
- Spring Boot: Auto-configuration, Starters, `application.yml`
- Spring MVC: REST API, Controllers, Exception handling, Bean Validation
- Spring Data JPA: Repository pattern, Entity mapping, Hibernate, JPQL/Criteria
- Spring Security: Authentication, Authorization, JWT, OAuth2
- Spring Boot Testing: JUnit 5, Mockito, `@SpringBootTest`, `@DataJpaTest`, MockMvc

## Cấu trúc Mono-repo

```
java-spring-boot-fundamentals/
├── .claude/                      # Claude Code config & skills
├── 01-spring-core/               # IoC, DI, Bean lifecycle
├── 02-spring-mvc/                # REST API, Controllers, Validation
├── 03-spring-data-jpa/           # JPA, Hibernate, Repositories
├── 04-spring-security/           # Security, JWT
├── 05-spring-boot-testing/       # Test strategies
└── ...                           # Sub-project mới thêm theo tiến độ
```

Mỗi sub-project là một Spring Boot project độc lập với `pom.xml` riêng và `README.md` ghi chú về chủ đề đang học.

## Tech Stack

| Thành phần | Lựa chọn |
|-----------|---------|
| Java | 21 (LTS) |
| Spring Boot | Latest stable |
| Build tool | Maven (mặc định) |
| Database (mặc định) | MySQL 8 (Docker) |
| Database (in-memory, quick test) | H2 embedded — optional, không cần setup |
| Database (khác khi cần) | SQL Server, MongoDB |
| Testing | JUnit 5 + Mockito + Spring Test |

## Chạy Sub-project

```bash
cd [sub-project-name]

# Chạy ứng dụng
./mvnw spring-boot:run

# Chạy tất cả tests
./mvnw test

# Chạy một test class cụ thể
./mvnw test -Dtest=UserServiceTest

# Build JAR
./mvnw clean package

# Build, bỏ qua tests
./mvnw clean package -DskipTests
```

## Database Mặc Định — MySQL

Các sub-project chủ yếu dùng **MySQL 8** (chạy qua Docker). Cấu hình mẫu trong `application.properties`:

```properties
spring.datasource.url=jdbc:mysql://${MYSQL_HOST:localhost}:3306/springdb?useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=112233
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.open-in-view=false
```

Chạy MySQL bằng Docker (đặt `docker-compose.yml` trong sub-project):

```bash
docker compose up -d    # Start
docker compose down     # Stop (giữ data)
docker compose down -v  # Stop + xóa data
```

> Database khác khi cần: **SQL Server** (`com.microsoft.sqlserver:mssql-jdbc`) hoặc **MongoDB** (`spring-boot-starter-data-mongodb`, NoSQL — không dùng JPA).

## H2 (tùy chọn — in-memory, quick test)

Khi muốn chạy thử nhanh, zero-setup (không cần Docker/cài DB) — vd project học thuần logic:

```properties
spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true
```

H2 Console: `http://localhost:8081/h2-console` (JDBC URL: `jdbc:h2:mem:testdb`, User: `sa`)

## Ghi Chú Học Tập

- Mỗi sub-project có `README.md` ghi: khái niệm đang học, cách chạy, điểm quan trọng
- Dùng `/teach` để tạo bài học có cấu trúc với quiz và reference docs
- Dùng `/grill-me` để tự kiểm tra hiểu biết về một concept
- Dùng `/study-repo` để phân tích Spring Boot repo mẫu trên GitHub
- Dùng `/tdd` khi implement feature để hiểu behavior qua tests
- Dùng `/resume` đầu mỗi session để Claude nạp lại context đã biết về repo (cấu trúc, trạng thái từng sub-project, known issues, chi tiết auth của project 09) thay vì phải phân tích lại từ đầu — xem `.claude/skills/resume/`
