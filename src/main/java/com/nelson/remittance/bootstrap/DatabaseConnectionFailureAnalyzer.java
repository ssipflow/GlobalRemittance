package com.nelson.remittance.bootstrap;

import java.sql.SQLException;
import java.util.regex.Pattern;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

// DB 접속 실패가 Hibernate의 Dialect 결정 오류나 Flyway의 접속 실패로만 보이는 경우 원인과 조치를 안내한다.
// Flyway가 Hibernate보다 먼저 접속하므로 실제 접속 실패는 대개 Flyway 경로로 드러난다.
// 비밀번호·사용자명·호스트·URL·예외 메시지는 출력하지 않으며 분류는 SQLState만 쓴다.
public class DatabaseConnectionFailureAnalyzer extends AbstractFailureAnalyzer<Throwable> {
    private static final String MARKER = "Unable to determine Dialect without JDBC metadata";
    private static final String FLYWAY_CONNECT_MARKER = "Unable to obtain connection from database";
    private static final int MAX_DEPTH = 20;
    private static final Pattern SQLSTATE_FORMAT = Pattern.compile("^[0-9A-Z]{5}$");
    private static final String SEE_WARN =
            "이 안내 바로 위 WARN 로그 줄에 라이브러리의 원래 메시지가 있습니다(사용자명·호스트가 포함될 수 있으니 공유 시 주의하세요).";
    private static final String H2_FIXED_PASSWORD =
            "H2 파일 DB는 비밀번호가 처음 생성할 때 고정됩니다. data/remittance.trace.db에서 'wrong user or password' 기록을 확인하세요.";
    private static final String H2_RESET = "버려도 되는 DB라면 data 디렉터리를 삭제한 뒤 다시 실행하세요.";
    private static final String PG_ACCESS_RULES = "사용자·비밀번호와 서버의 접근 규칙(pg_hba.conf)도 확인하세요.";

    @Override
    // 원인 연쇄에서 표지 문구를 가진 Hibernate 예외를 찾고 없으면 null로 다른 분석기에 맡긴다.
    protected FailureAnalysis analyze(Throwable rootFailure, Throwable cause) {
        Throwable t = rootFailure;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = next(t)) {
            if (isFlywayConnectionFailure(t)) {
                return analyzeFlyway(t);
            }
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

    // Flyway 접속 실패만 잡고 마이그레이션 SQL 실패 같은 다른 Flyway 오류는 건드리지 않는다.
    private static boolean isFlywayConnectionFailure(Throwable t) {
        return t.getClass().getName().startsWith("org.flywaydb.")
                && t.getClass().getSimpleName().equals("FlywaySqlException")
                && t.getMessage() != null
                && t.getMessage().startsWith(FLYWAY_CONNECT_MARKER);
    }

    // Flyway 접속 실패의 SQLState로 원인을 분류한다. 인증 실패를 우선하고 분류 불가여도 일반 안내를 돌려준다.
    // 안내 블록이 출력되면 스택은 나오지 않으므로 원래 메시지가 있는 WARN 로그 줄을 가리킨다.
    private static FailureAnalysis analyzeFlyway(Throwable flywayFailure) {
        boolean auth = false;
        boolean unreachable = false;
        boolean noDatabase = false;
        boolean postgres = false;
        boolean h2 = false;
        String state = null;
        for (SQLException e : sqlExceptions(flywayFailure)) {
            // DB 종류는 클래스명으로만 구분하고 메시지는 읽지 않는다.
            String className = e.getClass().getName();
            postgres |= className.startsWith("org.postgresql.");
            h2 |= className.startsWith("org.h2.");
            String s = e.getSQLState();
            // 출력에 들어가는 값이므로 SQLState 형식(영숫자 5자)이 아니면 무시한다.
            if (s == null || !SQLSTATE_FORMAT.matcher(s).matches()) {
                continue;
            }
            if (s.startsWith("28")) {
                auth = true;
            } else if (s.equals("3D000")) {
                noDatabase = true;
            } else if (s.startsWith("08")) {
                unreachable = true;
            } else {
                continue;
            }
            // 안내와 같은 우선순위(인증, DB 없음, 연결 불가)로 대표 코드를 고른다.
            if (state == null || rank(s) < rank(state)) {
                state = s;
            }
        }
        String code = state == null ? "" : " (SQLState " + state + ")";
        if (auth) {
            String action =
                    "1. REMITTANCE_DB_PASSWORD가 DB를 만들 때 사용한 값과 같은지 확인하세요."
                            + " postgres 프로파일이면 REMITTANCE_DB_USER도 확인하세요.\n";
            int n = 2;
            if (postgres == h2) {
                // 종류를 모르거나 섞여 있으면 두 안내를 모두 유지한다.
                action += n++ + ". " + H2_FIXED_PASSWORD + "\n" + n++ + ". " + H2_RESET + "\n";
                action += n++ + ". " + PG_ACCESS_RULES + "\n";
            } else if (h2) {
                action += n++ + ". " + H2_FIXED_PASSWORD + "\n" + n++ + ". " + H2_RESET + "\n";
            } else {
                action += n++ + ". " + PG_ACCESS_RULES + "\n";
            }
            action += n + ". " + SEE_WARN;
            return new FailureAnalysis(
                    "데이터베이스가 인증(사용자/비밀번호)을 거부해 Flyway가 접속하지 못했습니다." + code, action, flywayFailure);
        }
        if (noDatabase) {
            return new FailureAnalysis(
                    "지정한 데이터베이스가 서버에 없어 Flyway가 접속하지 못했습니다." + code,
                    "REMITTANCE_DB_URL의 데이터베이스 이름이 맞는지, 해당 데이터베이스를 먼저 만들었는지 확인하세요.\n" + SEE_WARN,
                    flywayFailure);
        }
        if (unreachable) {
            return new FailureAnalysis(
                    "데이터베이스 서버에 연결하지 못해 Flyway가 접속하지 못했습니다." + code,
                    "1. DB 서버가 기동 중인지 확인하세요.\n"
                            + "2. postgres 프로파일이면 REMITTANCE_DB_URL의 호스트와 포트가 맞는지 확인하세요.\n"
                            + "3. 방화벽이나 컨테이너 네트워크 설정을 확인하세요.\n"
                            + "4. "
                            + SEE_WARN,
                    flywayFailure);
        }
        return new FailureAnalysis(
                "데이터베이스 접속에 실패해 Flyway가 시작하지 못했습니다. SQLState만으로는 원인을 분류할 수 없습니다.",
                SEE_WARN
                        + "\n비밀번호·사용자(REMITTANCE_DB_PASSWORD, REMITTANCE_DB_USER),"
                        + " 접속 주소(REMITTANCE_DB_URL), DB 서버 기동 여부, 데이터베이스 존재 여부를 점검하세요.",
                flywayFailure);
    }

    private static int rank(String sqlState) {
        return sqlState.startsWith("28") ? 0 : sqlState.equals("3D000") ? 1 : 2;
    }

    // 예외 하위 연쇄의 SQLException과 그 nextException 목록을 모은다. 순환은 깊이 제한으로 끊는다.
    private static java.util.List<SQLException> sqlExceptions(Throwable from) {
        java.util.List<SQLException> found = new java.util.ArrayList<>();
        Throwable t = from;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = next(t)) {
            if (t instanceof SQLException sql) {
                SQLException e = sql;
                for (int n = 0; e != null && n < MAX_DEPTH; n++, e = e.getNextException()) {
                    found.add(e);
                }
            }
        }
        return found;
    }

    // 자기 자신을 원인으로 가리키는 순환을 끊고 다음 원인을 돌려준다.
    private static Throwable next(Throwable t) {
        return t.getCause() == t ? null : t.getCause();
    }
}
