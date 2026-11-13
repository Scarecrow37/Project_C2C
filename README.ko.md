# Project_C2C (Call to Calendar)

[English](README.md) | **한국어**

통화 녹음을 바탕으로 캘린더에 일정을 등록하고 알림을 보내는 Android 앱입니다.
통화 녹음을 **기기 안에서** 텍스트로 바꾸고, **Claude**가 합의된 약속을 찾아 캘린더에 자동으로 등록합니다.

## 동작 방식

```
통화 종료
  └─ 갤럭시 기본 통화녹음이 .m4a 저장 (Recordings/Call/)
       └─ WorkManager가 MediaStore 변경 감지 (RecordingTriggerWorker)
            └─ CallProcessingWorker
                 1. 오디오 디코딩 → 16kHz 모노 PCM        (AudioDecoder, MediaCodec)
                 2. 기기 내 음성 인식                     (whisper.cpp, 한국어)
                 3. 일정 추출                             (Claude API, 구조화 출력)
                 4. 캘린더 등록 + 30분 전 알림            (CalendarContract)
                 5. "일정 추가됨" 알림 + [취소] 버튼
```

- **음성 파일은 기기 밖으로 나가지 않습니다.** Claude API로는 녹취록 텍스트만 전송됩니다.
- 합의된 약속만 등록합니다. "언제 한번 보자" 같은 막연한 말, 취소된 계획, 지난 일정은 건너뜁니다. 확신도가 기준값(기본 0.6)보다 낮은 일정도 건너뜁니다.
- "내일", "다음 주 화요일 3시" 같은 상대적 표현은 녹음 파일명에서 읽은 통화 시각을 기준으로 실제 날짜로 바꿉니다.
- 알림의 **취소** 버튼을 누르면 등록된 일정이 삭제됩니다.

## 생성된 일정 관리

앱의 **"생성된 일정"** 탭에서 통화로 자동 생성된 일정을 모아 볼 수 있습니다.

- **목록**: 다가오는 일정과 지난 일정으로 나누어 보여 줍니다. 일정마다 시간, 장소, 통화 상대를 표시합니다.
- **상세 보기**: 항목을 누르면 일시, 장소, 메모와 함께 통화 상대, 근거가 된 말, 녹음 파일, 자동 등록 시각을 볼 수 있습니다. 상세 화면 아래의 **수정**·**삭제** 버튼으로 바로 처리할 수 있습니다.
- **밀어서 삭제**: 목록에서 항목을 오른쪽에서 왼쪽으로 밀면 캘린더에서 지워집니다. 잘못 지웠으면 스낵바의 **되돌리기**로 복구할 수 있습니다.
- **수정**: 제목, 종일 여부, 시작·종료 날짜와 시간, 장소, 메모를 바꿀 수 있습니다. 시작 시간을 옮기면 일정 길이를 유지하도록 종료 시간도 함께 옮겨집니다.
- **캘린더와 동기화**: 일정 내용의 원본은 기기 캘린더입니다. 구글 캘린더나 삼성 캘린더 앱에서 수정하거나 삭제해도 목록에 바로 반영됩니다.
- "일정 추가됨" 알림을 누르면 앱이 열리면서 해당 일정의 상세 화면이 바로 뜹니다.

## 플랫폼 제약

| | |
|---|---|
| **iOS** | 지원하지 않습니다. 서드파티 앱은 통화 음성에 접근할 수 없습니다. |
| **Android 10 이상** | 일반 앱은 통화를 직접 녹음할 수 없습니다. 이 앱은 **휴대폰 기본 통화녹음**(삼성 갤럭시 등)이 저장한 파일을 읽습니다. |
| 권장 기기 | 삼성 갤럭시(One UI). 전화 앱 → 설정 → 통화 녹음 → **통화 자동 녹음**을 켜 두세요. |

## 프로젝트 구조

```
core/   순수 Kotlin/JVM 모듈: Android SDK 없이 빌드·테스트 가능
  ClaudeScheduleExtractor   Claude API 호출 (anthropic-java SDK, JSON 스키마 출력, 서버 측 fallback)
  ExtractionPrompt          시스템 프롬프트와 출력 스키마
  ExtractionParser          JSON → ScheduleEvent (검증·보정)
  EventFilter               확신도, 지난 일정, 중복 필터링
  EventDraft / CalendarTime 일정 수정 내용 검증, 캘린더 시각 변환
  EventFormat               일정 시간 표시 문자열
  RecordingFileName         삼성 녹음 파일명에서 통화 상대와 통화 시각 추출

app/    Android 앱 (Kotlin, Jetpack Compose, WorkManager)
  recording/  MediaStore에서 통화 녹음 찾기
  stt/        오디오 디코딩, whisper.cpp JNI, 모델 다운로드
  calendar/   캘린더 일정 추가·조회·수정·삭제
  data/       설정, 처리 기록, 생성된 일정 목록
  notify/     알림과 취소 동작
  work/       백그라운드 처리 파이프라인
  ui/         생성된 일정 탭(목록·상세·수정·밀어서 삭제), 설정 탭
  src/main/cpp/  whisper.cpp JNI 브리지
```

## 빌드

필요 환경: Android Studio (또는 Android SDK 35 + NDK + CMake 3.22.1), JDK 17 이상.

```bash
# 1. whisper.cpp 소스 받기 (1회)
./scripts/fetch_whisper.sh

# 2. 앱 빌드 (local.properties의 sdk.dir 또는 ANDROID_HOME 필요)
./gradlew :app:assembleDebug

# 핵심 로직 테스트 (Android SDK 불필요)
./gradlew :core:test

# 실제 Claude API 추출 테스트 (키가 설정된 경우에만 실행)
ANTHROPIC_API_KEY=sk-ant-... ./gradlew :core:test
```

Android SDK가 없으면 `:app`은 자동으로 빠지고 `:core`만 빌드됩니다.

## 처음 실행할 때

1. **권한 허용**: 오디오 파일, 캘린더, 알림.
2. **Claude API 키 입력**: console.anthropic.com에서 발급받으세요.
3. **캘린더 선택**: 일정을 등록할 캘린더를 고릅니다.
4. **음성 인식 모델 다운로드**: `ggml-small-q5_1.bin`, 약 190MB. Wi-Fi 연결을 권장합니다.
5. **자동 처리 켜기**: 이 시점 이후에 녹음된 통화부터 처리합니다. 기존 녹음은 처리하지 않습니다.

**"녹음 파일로 테스트"** 버튼을 쓰면 에뮬레이터나 삼성이 아닌 휴대폰에서도 아무 오디오 파일로 전체 흐름을 시험할 수 있습니다.

## 설정과 설계상 선택

- **모델**: `claude-opus-5-5`, effort `medium`. 서버 측 fallback(`fallbacks: "default"`)을 켜 두어, 안전 분류기가 요청을 거절하면 다른 모델이 자동으로 다시 시도합니다.
- **음성 인식**: whisper.cpp `small` (q5_1 양자화). 한국어 정확도가 부족하면 `WhisperModel.FILE_NAME`을 `medium` 모델로 바꾸세요 (더 느리고 용량이 큽니다).
- **긴 통화**: 기기 성능에 따라 텍스트 변환에 수십 초에서 몇 분이 걸립니다. 포그라운드 작업으로 실행됩니다.

## ⚠️ 보안 주의: API 키

현재 앱은 Claude API를 **직접** 호출하며, 키는 기기의 앱 전용 저장소에 저장됩니다.
개인용이나 프로토타입으로는 괜찮지만, **다른 사용자에게 앱을 배포한다면 앱에 API 키를 넣지 마세요.** Claude를 호출하는 백엔드 서버를 두고, 앱은 그 서버를 호출하도록 바꿔야 합니다.

## 다음 단계 (아이디어)

- 등록 전 확인 모드 ("이 일정을 추가할까요?" 알림)
- 녹취록에서 화자 구분
- 캘린더 백엔드 서버와 사용자 계정 사용
- 중복 일정 감지 (캘린더에 이미 있는 일정)
