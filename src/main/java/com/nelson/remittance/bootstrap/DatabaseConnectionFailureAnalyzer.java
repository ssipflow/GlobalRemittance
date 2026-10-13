package com.nelson.remittance.bootstrap;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

// DB 접속 실패가 Hibernate의 Dialect 결정 오류로만 보이는 경우 원인과 조치를 안내한다. 비밀번호·URL·경로 값은 출력하지 않는다.
public class DatabaseConnectionFailureAnalyzer extends AbstractFailureAnalyzer<Throwable> {
    private static final String MARKER = "Unable to determine Dialect without JDBC metadata";

    @Override
    // 원인 연쇄에서 표지 문구를 가진 Hibernate 예외를 찾고 없으면 null로 다른 분석기에 맡긴다.
    protected FailureAnalysis analyze(Throwable rootFailure, Throwable cause) {
        Throwable t = rootFailure;
        for (int depth = 0; t != null && depth < 20; depth++, t = next(t)) {
            if (t.getClass().getName().startsWith("org.hibernate.")
                    && t.getMessage() != null
                    && t.getMessage().contains(MARKER)) {
                return new FailureAnalysis(
                        "데이터베이스에 접속하지 못해 JDBC 메타데이터를 얻을 수 없었고, 그래서 Hibernate가 Dialect를 결정하지 못했습니다.",
                        "1. REMITTANCE_DB_PASSWORD가 DB 파일을 처음 만들 때 사용한 값과 같은지 확인하세요.\n"
                                + "2. 기존 ./data/remittance가 있다면 data/remittance.trace.db에서 'wrong user or password' 기록이 있는지 확인하세요.\n"
                                + "3. 새로 시작해도 된다면 data 디렉터리를 삭제한 뒤 다시 실행하세요.\n"
                                + "4. SPRING_PROFILES_ACTIVE=local로 실행하면 연결 풀 로그에 실제 접속 실패 원인이 출력됩니다.",
                        t);
            }
        }
        return null;
    }

    // 자기 자신을 원인으로 가리키는 순환을 끊고 다음 원인을 돌려준다.
    private static Throwable next(Throwable t) {
        return t.getCause() == t ? null : t.getCause();
    }
}
