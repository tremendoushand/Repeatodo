# 구글 드라이브 동기화 설정 (개발자용, 한 번만)

앱의 다른 기능은 바로 동작하지만, **구글 드라이브 동기화**는 구글이 "이 앱이 진짜 Repeatodo인지" 확인할 수 있도록 구글 클라우드에 앱을 한 번 등록해야 해요. 비용은 들지 않아요. 약 15분 걸려요.

> 캘린더 연동은 폰의 캘린더를 직접 쓰기 때문에 이 설정이 필요 없어요.

## 1. 구글 클라우드 프로젝트 만들기

1. https://console.cloud.google.com 에 Daehyung 님 구글 계정으로 로그인해요.
2. 위쪽 프로젝트 선택 → **새 프로젝트** → 이름 `Repeatodo` → 만들기.

## 2. Google Drive API 켜기

1. 왼쪽 메뉴 **API 및 서비스 → 라이브러리**.
2. `Google Drive API` 검색 → **사용**.

## 3. OAuth 동의 화면 (Google Auth Platform)

1. **API 및 서비스 → OAuth 동의 화면**(또는 *Google Auth Platform → 브랜딩*)으로 가서 **시작하기**.
2. 앱 이름 `Repeatodo`, 사용자 지원 이메일, 대상 **외부(External)**, 개발자 연락처 이메일을 입력해요.
3. **데이터 액세스(범위)** 에서 **범위 추가** → `.../auth/drive.appdata` 를 골라 저장해요. 이 범위는 앱 전용 숨김 폴더만 쓰는 "민감하지 않은 범위"라 구글 심사가 필요 없어요.
4. **대상(Audience)** 에서:
   - 혼자 테스트할 때: *테스트* 상태로 두고 **테스트 사용자**에 본인 Gmail을 추가해요. (테스트 상태에서는 7일마다 다시 연결하라고 할 수 있어요.)
   - 오래 쓰거나 스토어에 올릴 때: **앱 게시(프로덕션으로 푸시)** 를 눌러요.

## 4. 안드로이드 OAuth 클라이언트 만들기

앱을 서명하는 키마다 하나씩 만들어요. 패키지 이름은 모두 `com.tremendoushand.repeatodo` 예요.

1. **API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → OAuth 클라이언트 ID**.
2. 애플리케이션 유형 **Android**, 패키지 이름 `com.tremendoushand.repeatodo`, **SHA-1 인증서 지문**을 넣고 만들기.

SHA-1 확인 방법:

| 어떤 앱을 쓸 때 | SHA-1 얻는 방법 |
|---|---|
| Android Studio에서 바로 설치한 앱(디버그) | 터미널에서 `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` (Windows는 `%USERPROFILE%\.android\debug.keystore`) |
| 내 서명 키로 만든 앱(릴리스) | `keytool -list -v -keystore repeatodo-release.jks -alias repeatodo` |
| 플레이스토어에서 받은 앱 | Play Console → 앱 → **테스트 및 출시 → 앱 무결성 → 앱 서명** 의 *앱 서명 키 인증서* SHA-1 |

> GitHub Actions가 만든 **디버그** APK는 빌드할 때마다 서명 키가 달라져서 드라이브 연결이 안 돼요. 드라이브까지 쓰려면 Android Studio로 설치하거나, [플레이스토어 가이드](PLAY_STORE_GUIDE.md#2-서명-키-만들기)대로 서명 키를 GitHub에 등록해서 **릴리스** APK를 쓰세요.

코드에 넣을 값(클라이언트 ID 등)은 없어요. 구글이 패키지 이름과 SHA-1로 앱을 알아봐요.

## 5. 확인

앱 → 설정 → **구글 계정 연결** → 계정 선택 → 허용. "동기화했어요"가 뜨면 끝이에요.

| 오류 메시지 | 원인 |
|---|---|
| `10:` 또는 `DEVELOPER_ERROR` | 패키지 이름이나 SHA-1이 등록한 것과 달라요. 4번을 다시 확인하세요 |
| 403 / 권한 없음 | Drive API를 켜지 않았거나(2번), 테스트 사용자에 계정이 없어요(3번) |
| 이 앱은 확인되지 않았습니다 | 테스트 상태의 정상 경고예요. **고급 → 이동**을 누르면 돼요 |
