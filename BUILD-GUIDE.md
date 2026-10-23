# Build Guide

This guide explains how to build Docker Manager from source on any machine (Linux, macOS or Windows, x86_64 or ARM64).

## TL;DR

```bash
# 1. Make sure JDK 8 is the active Java (see "Installing JDK 8" below)
java -version          # must print 1.8.x

# 2. Build (skipping tests)
./gradlew clean build -x test        # Linux / macOS
gradlew.bat clean build -x test      # Windows (cmd / PowerShell)

# 3. Result
ls build/libs/dockermanager-0.0.1-SNAPSHOT.jar
```

## 1. Technology stack

| Component     | Version                          | Notes                                         |
|---------------|----------------------------------|-----------------------------------------------|
| Java (JDK)    | **8 (1.8) only**                 | Newer JDKs are not supported by Gradle 2.6    |
| Gradle        | 2.6 (via the included wrapper)   | Do not use a system-wide Gradle install       |
| Groovy        | 2.3.x (resolved by Gradle)       | No separate install needed                    |
| Spring Boot   | 1.2.3.RELEASE                    |                                               |
| Build output  | Executable "fat" jar             | `build/libs/dockermanager-0.0.1-SNAPSHOT.jar` |

## 2. Prerequisites

1. **JDK 8.** A full JDK is required, a JRE is not enough. Any vendor works (Eclipse Temurin, Azul Zulu, Amazon Corretto, OpenJDK from your OS package manager, etc.).
2. **Internet access** (at least for the first build) to:
   - `services.gradle.org` (downloads Gradle 2.6, about 60 MB, only once)
   - `jcenter.bintray.com` (most dependencies; JCenter is read-only now but still serves artifacts)
   - `oss.sonatype.org` (snapshot repository declared in `build.gradle`)
3. **About 1 GB of free disk space** for the Gradle distribution and the dependency cache.
4. **Git**, to clone the repository.

You do **not** need Gradle, Groovy or Maven installed. The Gradle wrapper (`gradlew` / `gradlew.bat` plus `gradle/wrapper/`) downloads the right Gradle version by itself.

## 3. Installing JDK 8

Pick the option for your platform. After installing, open a **new** terminal and check with `java -version` (it must report `1.8.0_xxx`).

### Any platform: SDKMAN! (Linux, macOS, WSL)

```bash
curl -s "https://get.sdkman.io" | bash
sdk list java | grep -E "8\.0\.[0-9]+"     # pick an available JDK 8 identifier
sdk install java <identifier>               # e.g. 8.0.xxx-tem or 8.0.xxx-zulu
sdk use java <identifier>
```

### Linux

- **Debian / Ubuntu:** `sudo apt install openjdk-8-jdk` (on newer releases where it is not packaged, use SDKMAN! or a Temurin / Zulu tarball).
- **Fedora / RHEL / CentOS:** `sudo dnf install java-1.8.0-openjdk-devel`
- **Arch:** `sudo pacman -S jdk8-openjdk`
- **Manual tarball:** download a JDK 8 `.tar.gz` for your architecture (x64 or aarch64) from <https://adoptium.net> or <https://www.azul.com/downloads/>, extract it anywhere and point `JAVA_HOME` to the extracted folder.

If several JDKs are installed, select JDK 8 with `sudo update-alternatives --config java` (Debian/Ubuntu), `sudo alternatives --config java` (Fedora/RHEL), or simply set `JAVA_HOME` for the build (see below).

### macOS

- **Intel (x86_64):** `brew install --cask temurin@8`
- **Apple Silicon (arm64):** Temurin 8 does not ship a native arm64 macOS build. Use Azul Zulu 8, which does: `brew install --cask zulu@8`, or download it from <https://www.azul.com/downloads/>.

List installed JDKs and get the JDK 8 path with:

```bash
/usr/libexec/java_home -V
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
```

### Windows

- **winget:** `winget install EclipseAdoptium.Temurin.8.JDK`
- **Chocolatey:** `choco install temurin8`
- **Manual:** download the JDK 8 `.msi` from <https://adoptium.net> and enable the "Set JAVA_HOME" option in the installer.

### Using JDK 8 only for this build

You don't have to make JDK 8 your default Java. Setting `JAVA_HOME` in the current terminal is enough, because the Gradle wrapper uses it:

```bash
# Linux / macOS
export JAVA_HOME=/path/to/jdk8
export PATH="$JAVA_HOME/bin:$PATH"
```

```powershell
# Windows PowerShell
$env:JAVA_HOME = "C:\path\to\jdk8"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

```bat
:: Windows cmd
set JAVA_HOME=C:\path\to\jdk8
set PATH=%JAVA_HOME%\bin;%PATH%
```

## 4. Building

From the project root:

```bash
# Linux / macOS (if needed, make the wrapper executable first: chmod +x gradlew)
./gradlew clean build -x test
```

```bat
:: Windows
gradlew.bat clean build -x test
```

The first build downloads Gradle 2.6 and all dependencies, which takes a few minutes. Later builds take seconds.

**Expected output:**

```
:bootRepackage
...
BUILD SUCCESSFUL
```

**Artifacts** in `build/libs/`:

| File                                        | Description                                                    |
|---------------------------------------------|----------------------------------------------------------------|
| `dockermanager-0.0.1-SNAPSHOT.jar`          | Executable Spring Boot jar with all dependencies (about 80 MB) |
| `dockermanager-0.0.1-SNAPSHOT.jar.original` | Plain jar without dependencies (can be ignored)                |

Messages like `Unknown file extension: src/main/resources/application.yml` (from the license plugin) and Java "deprecated API" / "unchecked" notes are harmless warnings.

### Useful Gradle tasks

| Command                          | What it does                                      |
|----------------------------------|---------------------------------------------------|
| `./gradlew clean build -x test`  | Full build without running tests (recommended)    |
| `./gradlew compileGroovy`        | Compile main sources only (fast compile check)    |
| `./gradlew compileTestGroovy`    | Compile main and test sources                     |
| `./gradlew test`                 | Run the test suite (see section 6)                |
| `./gradlew bootRun`              | Run the application from sources                 |
| `./gradlew dependencies`         | Print the resolved dependency tree                |

## 5. Running the application

The application reads its runtime configuration from a `config/` directory in the **current working directory**. That directory is git-ignored, so you have to create it. A starter configuration is included in `sample-config/`.

```bash
# Linux / macOS
cp -r sample-config config
keytool -genkeypair -alias DockerManager -keyalg RSA -keypass changeit \
        -keystore config/keystore.jks -storepass changeit -dname "CN=DockerManager"
java -jar build/libs/dockermanager-0.0.1-SNAPSHOT.jar
```

```powershell
# Windows PowerShell
Copy-Item -Recurse sample-config config
keytool -genkeypair -alias DockerManager -keyalg RSA -keypass changeit `
        -keystore config\keystore.jks -storepass changeit -dname "CN=DockerManager"
java -jar build\libs\dockermanager-0.0.1-SNAPSHOT.jar
```

The application listens on port **8080**. See `readme.md` for how to configure hosts, services, authentication profiles, etc.

## 6. Running the tests

```bash
./gradlew test
```

- Without a `config/` directory, the Spring context tests (`DockermanagerApplicationTests`, `HostsControllerTest`, `ServiceBuildConfigurationTest`) fail with `FileNotFoundException: ./config/...`. Create `config/` and the keystore as shown in section 5 before running the tests.
- `DockerUtilsTest > can convert 'docker ps' status to status date` depends on the current date / time zone and may fail. This failure is unrelated to the build.
- Test reports are written to `build/reports/tests/index.html`.

To build even when tests fail, use `-x test` (as the `Dockerfile` does).

## 7. Building with Docker

The included `Dockerfile` builds the jar inside a container, so you don't need a local JDK:

```bash
docker build -t dockermanager .
docker run -p 8080:8080 dockermanager
```

Notes:

- The base image `java:8-jdk` is deprecated on Docker Hub and only exists for `amd64`. On ARM64 machines (Apple Silicon, ARM Linux) add `--platform linux/amd64` to `docker build` (it will be slower under emulation). Or, in your local copy, replace the first line with a maintained multi-arch JDK 8 image such as `FROM eclipse-temurin:8-jdk`.
- The image contains `sample-config` but not a `config/` directory. Mount your own configuration at runtime, for example `-v "$(pwd)/config:/opt/DockerManager/config"`.

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` | `gradle/wrapper/gradle-wrapper.jar` is missing | Make sure your checkout contains `gradle/wrapper/gradle-wrapper.jar`, then pull or re-clone. |
| `Could not determine java version from '11.x' / '17.x' / '21.x'` or other errors at Gradle startup | Gradle 2.6 is running on a JDK newer than 8 | Set `JAVA_HOME` to a JDK 8 (section 3). Check with `./gradlew -version`, which prints the JVM being used. |
| `JAVA_HOME is set to an invalid directory` | `JAVA_HOME` points to a non-existent folder or to a JRE | Point it to the root folder of a JDK 8 (the folder that contains `bin/javac`). |
| `FileNotFoundException: ~/.gradle/wrapper/dists/gradle-2.6-all/.../gradle-2.6-all.zip` | Corrupted or partial Gradle distribution in the local cache | Delete `~/.gradle/wrapper/dists/gradle-2.6-all` (Windows: `%USERPROFILE%\.gradle\wrapper\dists\gradle-2.6-all`) and build again. |
| `Could not resolve all dependencies ...` / connection timeouts | No access to `jcenter.bintray.com`, `services.gradle.org` or `oss.sonatype.org` | Check internet / firewall. Behind a proxy, add `systemProp.https.proxyHost`, `systemProp.https.proxyPort` (and the `http.` variants) to `~/.gradle/gradle.properties`. |
| `PKIX path building failed` / SSL handshake errors while downloading | Very old JDK 8 update with outdated CA certificates, or a corporate TLS proxy | Use a recent JDK 8 update, or import your corporate CA certificate into the JDK `cacerts`. |
| `Permission denied: ./gradlew` | The execute bit was lost (e.g. ZIP download, Windows checkout) | `chmod +x gradlew` |
| `/bin/sh^M: bad interpreter` | `gradlew` was checked out with Windows (CRLF) line endings | `git config core.autocrlf input` and re-checkout, or run `sed -i 's/\r$//' gradlew`. |
| `java.lang.OutOfMemoryError` during compilation | Not enough heap for the Groovy compiler | Add `org.gradle.jvmargs=-Xmx1g` to `gradle.properties` (project root or `~/.gradle`). |
| `FileNotFoundException: ./config/...` when running the jar or tests | Missing runtime configuration | See section 5. |

## 9. Build-related changes made to this repository

These are the only changes made so the project builds again. Nothing else was touched.

1. **Added `gradle/wrapper/gradle-wrapper.jar`** (Gradle 2.6 wrapper). It was missing, so `./gradlew` could not start.
2. **Embedded the `org.ventiv:spring-boot-webjars-requirejs` library sources.** Version `1.1.2` is not available in any public Maven repository anymore. Its 4 Java classes (Apache License 2.0, from <https://github.com/Ventiv-Technology/spring-boot-webjars-requirejs>) now live in `src/main/java/org/ventiv/webjars/requirejs/`, and the dependency line was removed from `build.gradle`. Package and class names are unchanged, so `@EnableWebJarsRequireJs` keeps working as before.
3. **Fixed a static type checking error** in `src/main/groovy/org/ventiv/docker/manager/metrics/store/JpaAdditionalMetricsStore.groovy` (line 79). Under `@CompileStatic` with Groovy 2.3, `List.findAll` returns `Collection`, which cannot be assigned to `List<String>`. An explicit `(List<String>)` cast was added on the same line, so no line numbers shifted in the file.
