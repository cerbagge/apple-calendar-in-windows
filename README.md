# Apple Calendar in Windows

Windows 11 작업 표시줄 달력에는 일정이 나오지 않습니다. 이 앱은 그 자리를 대신해, 트레이에서 **iCloud(Apple) 캘린더 일정을 보고 추가·수정·삭제**할 수 있게 해 줍니다.
Claude 가 같은 캘린더를 다룰 수 있도록 **MCP 서버**도 함께 들어 있습니다.

## 주요 기능

- 트레이 아이콘을 누르거나 `Win + Alt + C` 를 누르면 시계 위에 달력과 그날 일정이 뜹니다
- iCloud CalDAV 연동: 반복 일정·예외 회차, 하루 종일 일정, 알림, 메모
- 일정 추가·수정·삭제 (반복 일정은 "이 일정만 / 모든 반복" 선택)
- 여러 iCloud 계정을 한 달력에 함께 표시
- 동기화 버튼, 마우스 휠로 달 넘기기, 앞뒤 달 미리 받기
- Windows 다크/라이트 모드와 강조 색을 따라가는 Windows 11 스타일 화면
- 로그인할 때 자동 실행할 수 있는 exe (Java 런타임 포함, 약 64MB)
- Claude 용 MCP 서버: `list_calendars`, `list_events`, `add_event`

## 요구 사항

- Windows 10 / 11
- JDK 22 이상 (빌드와 exe 패키징에 `jpackage` 사용)
- iCloud **앱 암호**: Apple 계정 비밀번호가 아니라, [account.apple.com](https://account.apple.com) → 로그인 및 보안 → 앱 암호에서 만든 `xxxx-xxxx-xxxx-xxxx` 형식의 암호

## 빌드와 실행

1. [gradle.properties](gradle.properties) 의 `org.gradle.java.home` 을 내 PC 의 JDK 22 이상 경로로 바꿉니다.
2. 실행하거나 exe 를 만듭니다.

```bash
# 바로 실행
./gradlew run

# exe 만들기 → dist/iCloudCalendar/iCloudCalendar.exe
./gradlew packageApp
```

> 한글이 들어간 경로에서는 Gradle 이 이전 빌드 결과를 지우지 못하는 문제가 있어, 빌드 폴더를 `%LOCALAPPDATA%\iCloudCalendar\build` 에 둡니다.

로그인할 때 자동으로 켜려면 `iCloudCalendar.exe` 의 바로 가기를 시작 프로그램 폴더(`shell:startup`)에 넣으면 됩니다.

## 사용법

1. 처음 실행하면 Apple ID 와 앱 암호를 입력합니다.
2. 트레이 아이콘을 왼쪽 클릭하거나 `Win + Alt + C` 로 달력을 엽니다.
3. 트레이 아이콘을 오른쪽 클릭하면 메뉴(달력 열기 / 동기화 / 계정 관리 / 종료)가 나옵니다.
4. **계정 관리**에서 다른 iCloud 계정을 추가하거나 지울 수 있습니다.

계정 정보는 Windows DPAPI 로 암호화해 `%APPDATA%\iCloudCalendar\account.dat` 에 저장하며, 같은 Windows 사용자 계정에서만 풀 수 있습니다.

## Claude 에서 쓰기 (MCP)

먼저 앱에서 계정을 연결한 뒤, MCP 서버를 설치하고 Claude Code 에 등록합니다.

```bash
./gradlew installMcp
```

```bash
claude mcp add --scope user icloud-calendar -- "<JDK 경로>\bin\java.exe" --enable-native-access=ALL-UNNAMED -Xms8m -Xmx64m -XX:+UseSerialGC -cp "%LOCALAPPDATA%\iCloudCalendar\mcp\lib\*" icloudcal.McpServer
```

MCP 서버는 앱이 저장한 계정을 그대로 씁니다. MCP 가 실행 중일 때 `installMcp` 를 하면 jar 가 잠겨 실패하므로, Claude 를 닫고 설치하십시오.

## 라이선스

[CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/) (저작자 표시-비영리 4.0 국제). 전문은 [LICENSE](LICENSE) 에 있습니다.

- **비영리**: 상업적 목적으로 이용할 수 없습니다.
- **저작자 표시**: 이 코드를 쓰거나 고쳐서 배포할 때는 원저작자, 원본 저장소 링크, 라이선스, 변경 여부를 밝혀야 합니다.

출처 표시 예시:

```
Apple Calendar in Windows by cerbagge (https://github.com/cerbagge/apple-calendar-in-windows),
licensed under CC BY-NC 4.0. 원본을 일부 수정함.
```

iCloud, Apple 은 Apple Inc. 의 상표이며, 이 프로젝트는 Apple 과 관련이 없습니다.
