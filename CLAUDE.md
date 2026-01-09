# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Kotlin-based elevator simulation system** (JinBo电梯) with multiple components:
- **Elevator simulation core** - Real-time elevator control with height-based movement (not just floor-based)
- **Log viewer application** - Swing-based desktop GUI for viewing logs
- **HTTP/HTTPS servers** - Multiple web servers for API endpoints and testing
- **TCP server components** - For elevator device communication

The system supports three request sources:
1. **External requests** - From outside the elevator, requires direction matching (up/down)
2. **Internal requests** - From inside the elevator, no direction required
3. **TCP requests** - Customizable behavior via configuration

## Build System

- **Maven** (pom.xml) with Kotlin Maven plugin
- **Kotlin version**: 2.2.0
- **JVM target**: 1.8

### Common Commands

```bash
# Build the project
mvn compile

# Run tests
mvn test

# Clean build
mvn clean compile

# Run specific main class (example)
mvn exec:java -Dexec.mainClass="org.dao.device.DeviceApplicationKt"

# Create executable JAR
mvn package
```

## Architecture

### Key Packages

- `org.dao.device` - Main application entry points and HTTP servers
- `org.dao.device.lift.jinbo` - Core elevator simulation logic
- `org.dao.device.lift.jinbo.fe` - Elevator GUI (Swing-based)
- `org.dao.device.lv` - Log viewer service
- `org.dao.device.lv.fe` - Log viewer GUI (Swing-based)
- `org.dao.device.common` - Shared utilities (JSON, file operations, GUI events)

### Main Entry Points

1. **`DeviceApplication.kt`** - Main application with elevator simulation + web server (port 7070)
   - Starts Javalin web server with elevator API endpoints
   - Initializes elevator simulation system
   - API endpoints:
     - `POST /api/lift/{liftId}/request` - Request elevator to floor
     - `POST /api/lift/{liftId}/close` - Close elevator door
     - `POST /api/lift/{liftId}` - Get elevator status

2. **`HttpsServerApp.kt`** - Standalone HTTPS server (ports 8000 HTTP, 8001 HTTPS)
   - Uses mkcert-generated certificates for local development
   - Configured with TLS intermediate security

3. **`HttpsClientApp.kt`** - HTTP/HTTPS client for testing
   - Demonstrates certificate handling and HTTP requests

4. **Log viewer** - Can be started independently via `LogViewerService.init()`

### Core Components

#### Elevator Simulation (`JinBoServer.kt`)
- **Singleton pattern** - Global elevator instance
- **Real-time simulation** - Height-based movement with configurable floor heights
- **State management**:
  - `liftState`: UP, DOWN, IDLE
  - `doorState`: OPEN, OPENING, CLOSE, CLOSING, ERROR
  - `targetFloor`: Next destination floor
- **Concurrent processing** - Thread pools and synchronized blocks
- **Request queuing** - Optimized algorithm for elevator movement

#### Domain Models (`JinBoDomain.kt`)
- `JinBoConfig` - Elevator configuration (floors, heights, timings)
- `JinBoRuntime` - Runtime state (current floor, height, status)
- `JinBoReq` - Request model with source type (INTERNAL, EXTERNAL, TCP)
- `JinBoFloor` - Floor definition with label and height
- Enums: `JinBoLiftStatus`, `JinBoDoorStatus`, `JinBoReqSource`

#### HTTP Handlers (`JinBoHandler.kt`)
- RESTful API endpoints for elevator control
- JSON request/response format using Jackson
- Path parameters for resource identification

#### GUI Components
- **Elevator GUI** (`LiftFrame.kt`) - Visualizes elevator movement and status
- **Log viewer GUI** (`LogViewerFrame.kt`) - Swing-based log viewing interface
- **Event buses** (`LvEventBus.kt`, `JinBoEventBus.kt`) - GUI event communication

### Key Design Patterns

1. **Singleton** - Global services (`DeviceApplication`, `JinBoServer`, `JsonHelper`)
2. **Domain-Driven Design** - Clear separation of domain models
3. **Layered Architecture** - Presentation, business logic, data access layers
4. **Event-Driven** - GUI events via event buses
5. **Thread-safe concurrency** - `ConcurrentHashMap`, `CopyOnWriteArrayList`, synchronized blocks

## Development Notes

### Dependencies
- **Web Framework**: Javalin 6.7.0 with SSL plugin
- **JSON**: Jackson 2.14.2 (with Kotlin module and Java 8 time support)
- **Networking**: Netty 4.1.92, OkHttp 4.11.0
- **Logging**: SLF4J 2.0.17 + Log4j2 2.25.1
- **Utilities**: Apache Commons Lang3 3.12.0, Commons IO 2.20.0
- **Testing**: JUnit Jupiter 5.10.0

### Configuration Files
- `log4j2.xml` - Logging configuration in `src/main/resources/`
- HTTP test files in `src/test/http/` - For testing API endpoints
- SSL certificates - Configured for local development with mkcert

### Testing
- HTTP test files available in `src/test/http/`
  - `JinBo.http` - Elevator API tests
  - `x-www-form-urlencoded.http` - Form data tests
- Use `mvn test` to run unit tests
- Test structure follows Maven standard (`src/test/kotlin/`)

### Running Applications

```bash
# Main elevator application with web server
mvn exec:java -Dexec.mainClass="org.dao.device.DeviceApplicationKt"

# HTTPS server (requires certificate files)
mvn exec:java -Dexec.mainClass="org.dao.device.HttpsServerAppKt"

# HTTP/HTTPS client
mvn exec:java -Dexec.mainClass="org.dao.device.HttpsClientAppKt"
```

### Important Implementation Details

1. **Elevator Movement Algorithm**:
   - Height-based calculation (not just floor counting)
   - Unidirectional movement (complete all requests in one direction before reversing)
   - Request optimization based on current height and direction

2. **Door State Management**:
   - Five states: OPEN, OPENING, CLOSE, CLOSING, ERROR
   - Automatic door closing after configured time
   - Door must be closed before elevator can move

3. **Request Processing**:
   - External requests require direction matching
   - Internal requests cleared at top/bottom floors (configurable)
   - TCP requests customizable via configuration
   - Concurrent request handling with thread safety

4. **GUI Communication**:
   - Event buses for decoupled GUI updates
   - Real-time status updates via event listeners
   - Swing-based interfaces with custom panels

## File Structure Reference

```
src/main/kotlin/org/dao/device/
├── DeviceApplication.kt          # Main entry point
├── HttpsServerApp.kt            # HTTPS server
├── HttpsClientApp.kt            # HTTP/HTTPS client
├── lift/jinbo/
│   ├── JinBoServer.kt           # Core elevator logic
│   ├── JinBoDomain.kt           # Domain models
│   ├── JinBoHandler.kt          # HTTP handlers
│   └── fe/                      # Elevator GUI
├── lv/                          # Log viewer service
├── lv/fe/                       # Log viewer GUI
└── common/                      # Shared utilities

src/main/resources/
└── log4j2.xml                   # Logging configuration

src/test/http/                   # HTTP test files
```

## Requirements Reference

See `ReadMe.md` for detailed elevator requirements:
- External requests require direction matching
- Internal requests cleared at top/bottom floors
- TCP requests customizable
- Door must open at destination floors
- Unidirectional movement (no来回走)
- Height-based movement calculation
- Concurrent access protection

# 主线程协作配置

## 决策分层
- 自主决策: 代码风格、错误修复、明显的优化
- 快速咨询: 技术选型、架构模式 (超时自动决策)
- 重大决策: 业务逻辑变更、数据结构调整 (必须确认)

## 工作模式
- 启用并行处理
- 关键节点自动同步
- 保持状态透明度

## 异常处理
- 遇到冲突时暂停等待指示
- 不确定的业务需求立即询问
- 技术实现问题可以自主解决

# 团队主线程模式

## 角色定义
- 产品经理: 业务需求主线程
- 技术负责人: 架构决策主线程
- 开发者: 实现细节主线程
- Claude: 高效工作线程

## 协作流程
1. 需求分析 - 产品经理主导
2. 技术设计 - 技术负责人主导
3. 具体实现 - 开发者主导
4. 代码生成 - Claude 辅助执行


# 自动计划模式配置

## 执行策略
- 启用自动计划模式
- 风险阈值: 中等
- 自动执行类型: 文档更新、代码格式化、简单修复

## 安全策略
- 数据库操作: 总是需要确认
- 配置文件修改: 中风险以上需要确认
- 测试文件: 自动执行

## 监控策略
- 启用实时监控
- 错误自动回滚
- 关键操作日志记录