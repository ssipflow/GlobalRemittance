package com.nelson.remittance.adapter.web.error;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// 예외를 개인정보 없이 설명한다. getMessage()/toString()은 요청 값이 섞일 수 있어 절대 사용하지 않는다.
final class ErrorDescriptions {
    private static final int MAX_CAUSES = 10;
    private static final int MAX_FRAMES = 5;
    private static final String APP_PACKAGE = "com.nelson.remittance";

    private ErrorDescriptions() {}

    // 원인 연쇄의 클래스 이름, SQLState/벤더 코드, 애플리케이션 스택 프레임 일부를 한 줄로 만든다.
    static String describe(Throwable error) {
        StringBuilder out = new StringBuilder("causes=[");
        Throwable current = error;
        for (int i = 0; current != null && i < MAX_CAUSES; i++) {
            if (i > 0) {
                out.append(" <- ");
            }
            out.append(current.getClass().getName());
            if (current instanceof SQLException sql) {
                out.append("(sqlState=")
                        .append(sql.getSQLState())
                        .append(", vendorCode=")
                        .append(sql.getErrorCode())
                        .append(')');
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        out.append("] frames=").append(frames(error));
        return out.toString();
    }

    // 첫 예외의 스택 중 애플리케이션 패키지 프레임만 클래스.메서드:줄 형태로 최대 5개 남긴다.
    private static List<String> frames(Throwable error) {
        List<String> frames = new ArrayList<>();
        for (StackTraceElement e : error.getStackTrace()) {
            if (frames.size() >= MAX_FRAMES) {
                break;
            }
            if (e.getClassName().startsWith(APP_PACKAGE)) {
                frames.add(e.getClassName() + "." + e.getMethodName() + ":" + e.getLineNumber());
            }
        }
        return frames;
    }
}
