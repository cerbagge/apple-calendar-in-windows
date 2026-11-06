plugins {
    application
}

// 한글 경로(03_코딩) 안에서는 Gradle 이 이전 빌드 출력물을 지우지 못해 두 번째 빌드부터 실패하므로
// build 폴더를 %LOCALAPPDATA%\iCloudCalendar\build 에 둔다
layout.buildDirectory.set(File(System.getenv("LOCALAPPDATA"), "iCloudCalendar/build"))

group = "icloudcal"
version = "3.2.0"

repositories {
    mavenCentral()
}

val javafxVersion = "23.0.2"

dependencies {
    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:win")
    }
    implementation("net.sf.biweekly:biweekly:0.6.8")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// 트레이에 상주하는 작은 창이라 Java 힙(실사용 20MB 안팎)보다 GPU 렌더링(D3D), G1 GC, C2 컴파일러가 쓰는
// 네이티브 메모리가 훨씬 크다. 소프트웨어 렌더링 + SerialGC + C1 만 쓰면 개인 메모리가 약 400MB → 100MB 로 준다.
// -Xms 를 주지 않으면 초기 힙이 RAM 의 1/64 로 잡혀 -Xmx 전체를 처음부터 확보하므로 꼭 함께 준다.
val jvmOptions = listOf(
    "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED",
    "-Xms16m", "-Xmx128m", "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
    "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=32m", "-Xss512k",
    "-Dprism.order=sw", "-Dsun.java2d.d3d=false",
)

// exe 에 넣을 Java 모듈: jdeps 결과 + 한국어 날짜 형식(jdk.localedata), 한글 Windows 인코딩 MS949(jdk.charsets)
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.logging", "java.net.http", "java.sql", "java.xml",
    "jdk.charsets", "jdk.jfr", "jdk.localedata", "jdk.unsupported",
)

application {
    mainClass.set("icloudcal.Launcher")
    applicationDefaultJvmArgs = jvmOptions
}

tasks.test {
    useJUnitPlatform()
}

// MCP 서버를 %LOCALAPPDATA%\iCloudCalendar\mcp\lib 에 설치한다.
// 실행 중인 MCP 서버가 build 폴더의 jar 를 잡고 있으면 다시 빌드할 수 없으므로 따로 복사해 둔다.
tasks.register<Sync>("installMcp") {
    from(tasks.jar)
    from(configurations.runtimeClasspath) { exclude("javafx-*") }
    into(File(System.getenv("LOCALAPPDATA"), "iCloudCalendar/mcp/lib"))
}

// dist/iCloudCalendar/iCloudCalendar.exe 를 만든다 (Java 런타임 포함, 설치 없이 실행)
tasks.register<Exec>("packageApp") {
    dependsOn("installDist")
    val lib = layout.buildDirectory.dir("install/${project.name}/lib").get().asFile
    val out = layout.projectDirectory.dir("dist").asFile
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage.exe")
    doFirst { out.deleteRecursively() }
    commandLine(
        listOf(
            jpackage.path, "--type", "app-image",
            "--name", "iCloudCalendar",
            "--app-version", version.toString(),
            "--input", lib.path,
            "--main-jar", "${project.name}-$version.jar",
            "--main-class", "icloudcal.Launcher",
            "--add-modules", runtimeModules.joinToString(","),
            "--jlink-options",
            "--strip-debug --strip-native-commands --no-man-pages --no-header-files --compress=zip-6 --include-locales=en,ko",
            "--dest", out.path,
        ) + jvmOptions.flatMap { listOf("--java-options", it) }
    )
}
