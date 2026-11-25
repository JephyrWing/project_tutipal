-- PT-14: 소셜 로그인에서 이메일을 받지 않는다(카카오 account_email은 비즈 앱 전용이라 로그인 실패 원인).
-- 컬럼은 남겨 두고(팀 결정, 병합된 V2는 수정하지 않음) 이전에 저장된 값만 지운다.
UPDATE users SET email = NULL WHERE email IS NOT NULL;

COMMENT ON COLUMN users.email IS '미사용(PT-14, 2026-11-20부터 수집 안 함). 항상 NULL.';
