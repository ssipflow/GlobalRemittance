-- 이메일(user_id)을 소문자로 정규화한다. 앱은 가입·로그인에서 소문자(Locale.ROOT)로 바꿔 쓰므로,
-- 이미 대소문자가 섞여 저장된 계정도 같은 규칙으로 맞추지 않으면 로그인할 수 없다.
-- 정규화 후 같아지는 계정이 있으면 어떤 행도 바꾸기 전에 명시적 예외로 실패한다.
-- 계정을 조용히 합치거나 한쪽을 지우지 않으므로 운영자가 충돌 계정을 확인해 직접 정리한 뒤 다시 실행해야 한다.
-- 한계: PostgreSQL의 LOWER는 데이터베이스 로캘(LC_CTYPE)에 의존해 앱의 Locale.ROOT 소문자 변환과
-- 비 ASCII 문자에서 다를 수 있다. 충돌 검사는 LOWER 결과 기준이며 유일 제약이 최종 방어다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users GROUP BY LOWER(user_id) HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'V3 중단: 대소문자만 다른 중복 user_id가 있어 소문자 정규화를 적용할 수 없다. 수동 정리가 필요하다.';
    END IF;
END
$$;

UPDATE users SET user_id = LOWER(user_id) WHERE user_id <> LOWER(user_id);
