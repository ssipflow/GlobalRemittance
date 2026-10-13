# readme-db-password

- 배경: 사용자 지적. 설정(`REMITTANCE_DB_PASSWORD`)은 있지만 README 실행 안내에 없다.
- 범위: README.md 실행 절, overview.md 환경변수 표, .gitignore(`secret.local`). 코드·설정 변경 없음.
- 직접 확인한 사실(스크래치 H2 파일 DB, 앱 기동 + H2 JDBC Shell): `secretA`로 처음 기동하면 DB가 그 비밀번호로 만들어지고,
  이후 다른 비밀번호나 빈 값은 `Wrong user name or password [28000-220]`로 거부된다. 앱 로그에는 인증 오류 대신
  `Unable to determine Dialect without JDBC metadata`만 남는다(SqlExceptionHelper 로그 차단의 부작용).
- 확인하지 않은 것: 기존 DB의 `ALTER USER` 절차 실행, 파일 암호화(CIPHER) 옵션. README는 이 둘을 일반 지식으로만 언급한다.
- `secret.local`: 로컬 테스트 키 보관용 비추적 파일. `.gitignore`에 추가해 제외를 `git check-ignore`로 확인한 뒤 만들었다.
  내용(AES·JWT 키, 주석 처리한 DB 비밀번호)은 저장소·문서·대화에 기록하지 않는다. 기존 `./data/remittance`가 있어 DB 비밀번호 줄은 주석으로 둔다.
- 후속 근거: 백로그 '개인정보를 제거한 오류 관측'에 인증 실패 원인 비가시성이라는 실제 사례가 추가됐다.
