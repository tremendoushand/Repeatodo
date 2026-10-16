# Repeatodo

매일·매주·매월 반복하는 할 일을 한 번의 탭으로 체크하고, 주간·월간 달성률을 확인하는 안드로이드 앱입니다.

- 반복: 매일 / 매주(요일 지정, 주중 아무 때나) / 매월(날짜, 말일, 월중 아무 때나)
- 알림: 할 일별 알림 시간, 알림 시간이 없는 일은 정해 둔 시간에 모아서 알림, 알림에서 바로 완료
- 횟수: 한 번에 N회, 총 N번 하면 자동 종료
- 대시보드: 주간 표, 월간 달력, 카테고리별·할 일별 달성률, 연속 기록
- 카테고리: 이름·색상·순서 직접 관리
- 캘린더 연동: 기기 캘린더에 반복 일정으로 표시
- 구글 드라이브 동기화: 주기, Wi-Fi 전용 여부 선택 / JSON 백업·복원

## 문서

- [사용자 가이드](docs/USER_GUIDE.md)
- [설계](docs/DESIGN.md)
- [구글 드라이브 동기화 설정](docs/GOOGLE_SETUP.md) (개발자 1회 설정)
- [플레이스토어 판매 가이드](docs/PLAY_STORE_GUIDE.md)
- [개인정보처리방침](docs/PRIVACY_POLICY.md)

## 빌드

Kotlin, Jetpack Compose, Material 3, Room, WorkManager, DataStore. minSdk 26, targetSdk 36.

- **Android Studio**: 이 폴더를 열고 ▶ 실행. 폰을 USB로 연결하고 개발자 옵션의 USB 디버깅을 켜면 바로 설치돼요.
- **GitHub Actions**: `main`에 push하면 테스트와 빌드가 돌고, 실행 결과 페이지의 **Artifacts**에서 `repeatodo-debug-apk`를 받아 폰에 설치할 수 있어요.
- **터미널**: `./gradlew assembleDebug` (JDK 17, Android SDK 필요)

## 구조

```
app/src/main/java/com/tremendoushand/repeatodo/
  domain/    반복 규칙, 달성률, 동기화 병합 (순수 Kotlin, 단위 테스트)
  data/      Room DB, DataStore 설정, TaskRepository
  notify/    알람 예약, 알림, 재부팅 후 재예약
  calendar/  기기 캘린더 연동
  sync/      구글 드라이브 인증, REST 클라이언트, WorkManager 동기화
  ui/        오늘, 대시보드, 할 일, 카테고리, 설정 화면
```
