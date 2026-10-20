# 검증 결과

2026-10-20, 작업 환경 기준
- 수정: 검증되지 않은 2화 번호 추정 제거. 사이트의 실제 회차 링크를 읽는 방식 사용.
- 수정: PNG 캐시가 최대 두 회차를 기억하도록 변경.

- 테스트 대상: `GlyphMatrix.kt`, `PortablePng.kt`, `FlowPages.kt`
- 로컬 `kotlinc` 컴파일: 성공
- 자체 생성 PNG 파일 쓰기: 성공
- PIL PNG 디코딩 및 크기 확인: 성공 (744 × 1144, 1-bit grayscale)
- Android SDK/Gradle로 전체 APK 빌드: 실행하지 못함 (필수 SDK/Gradle 의존성 없음)
- Bookkor 원본 HTML의 CSS 파싱 테스트: 실행하지 못함 (컨테이너에서 사이트 DNS 접속 불가)
- Tachimanga/iOS 설치 테스트: 실행하지 못함 (실기기 없음)

**테스트 이미지**는 `preview.png`에 포함되어 있습니다. 실제 Bookkor 본문을 포함하지 않는 독립적인 예문입니다.

실패할 수 있는 조건: 사이트 HTML 셀렉터 변경, 사이트에서 앱 요청 거부, iOS 안의 미지원 JVM/JSoup 함수 호출, 확장 이미지 캐시 수명.
