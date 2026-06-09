# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the SpectraBlueStorm frontend project - a Java-based S3-compatible storage system using Gradle for build management. The codebase is organized as a multi-module Gradle project with several core components.

## Essential Commands

### Build and Package
- `./gradlew clean build` - Clean and build all modules
- `./packageAll.sh` - Package all components for deployment (runs: `./gradlew clean common:compileSql DataPlanner:distTar server:war simulator:distTar`)

### Testing
- `./testAll.sh` - Run complete test suite including integration tests
- `./testMost.sh` - Run tests with most integration tests skipped for faster execution
- `./gradlew test` - Run unit tests for all modules
- `./gradlew :moduleName:test` - Run tests for specific module

#### Test Environment Variables
- `SKIP_CI_TESTS=true` - Skip CI-specific tests
- `SKIP_RPC_TESTS=true` - Skip RPC integration tests
- `SKIP_DATAPLANNER_TESTS=true` - Skip DataPlanner integration tests
- `SKIP_PUBLIC_CLOUD_TESTS=true` - Skip public cloud integration tests
- `SKIP_DOCKER_INTEGRATION=true` - Skip Docker-based integration tests

### Code Quality
- `./auditCode.sh` - Run Spectra code formatting audit tool

### Development
- `./gradlew tasks` - List available Gradle tasks
- `./gradlew :moduleName:run` - Run specific modules (DataPlanner, integrationtests, simulator)

## Architecture

### Module Structure
The project consists of 6 main modules with clear dependencies:

1. **util** - Base utilities and shared test classes
2. **common** - Core domain objects, shared business logic, and platform services (depends on util)
3. **target** - Target storage integration (depends on common, includes AWS S3 SDK)
4. **DataPlanner** - Data planning and management logic (depends on common, target, simulator)
5. **server** - Web server and HTTP request handling (depends on common, produces WAR)
6. **simulator** - Storage simulation for testing (depends on common, produces TAR distribution)

### Key Technologies
- **Java 17** (sourceCompatibility/targetCompatibility)
- **Gradle 8.1.1** with multi-module structure
- **JUnit 5** (with JUnit 4 vintage support for legacy tests)
- **Spring Framework 3.2.15**
- **Mockito** for testing
- **Lombok** for code generation
- **Log4j 2.17.2** for logging
- **AspectJ** (optional, enabled with `-Duse.aspectj` system property)

### Package Structure
All modules use the `com.spectralogic.s3` base package, following standard Maven/Gradle conventions with `src/main/java` and `src/test/java`.

## Development Notes

### Gradle Configuration
- Uses custom task ordering to ensure proper module test execution sequence
- Offline mode supported with `--offline` flag and dependency management
- Custom heap settings: `-Xmx3g` for AspectJ performance
- UTF-8 encoding enforced throughout

### Testing Strategy
- Unit tests use JUnit 5 with Mockito
- Integration tests tagged with specific categories (rpc-integration, dataplanner-integration, public-cloud-integration)
- Test environment supports selective test exclusion via environment variables
- Heap dump on OOM: configured for debugging memory issues

### Build Environment
- Supports both online and offline Gradle builds
- Custom GRADLE_USER_HOME handling for restricted build environments
- AspectJ support for thread leak detection during testing
- Docker integration for database migration testing (commented out)

### Dependencies
Key external dependencies include AWS SDK (1.11.780), PostgreSQL JDBC (42.2.20), Apache HTTP Client (4.5.9), and DS3 SDK (5.4.1).

### Additional Info from User:
This codebase runs as two separate java applications on a rack-mounted appliance called a Black Pearl. One is a tomcat implementation called "the server", another is called "the dataplanner." The server is almost entirely stateless and communicates with the dataplanner via an RPC interface. Both applications can access a shared postgres database via the BeansServiceManager. The dataplanner manages "tasks" that execute within its 5 "blob stores" (tape, disk pool (usually just called pool), s3, azure, ds3). S3, azure, and s3 are referred to as "targets" and are remote. A ds3 taret is a remote Black Pearl. Tape and pool are local storage. "Cache" is a local filesystem which is used as a landing space for IO. Each table in the database is represented as a "bean" class which always has an associated service class. This product is an object store where each object is comprised of one or more "blobs". Jobs used to be divided into job "chunks" but which had an entry for each blob, but chunks have now been removed in favor of having entries aggregate as needed per blob store. An XML api spec called request_handler_contract is generated automatically. When running tests, the database is created from the DAO definitions (the beans). In production, the migrations in sql/db/migrate are run. These must match the DAO definitions. 