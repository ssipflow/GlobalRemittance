package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

// 바이트코드 기반으로 클린 아키텍처 의존성 방향을 검증한다.
class ArchitectureTest {
    private static final Path CLASSES = Path.of("build/classes/java/main/com/nelson/remittance");

    // 내부 계층이 참조하면 안 되는 프레임워크·API 명세·외부 계층의 상수 풀 문자열 형식.
    private static final List<String> FORBIDDEN =
            List.of(
                    "org/springframework/",
                    "jakarta/persistence/",
                    "io/swagger/",
                    "org/springdoc/",
                    "com/nelson/remittance/adapter/");

    private static List<Path> classFiles(String layer) throws IOException {
        Path directory = CLASSES.resolve(layer);
        assertTrue(Files.isDirectory(directory));
        try (var files = Files.walk(directory)) {
            return files.filter(p -> p.toString().endsWith(".class")).toList();
        }
    }

    private static String constants(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    @Test
    // 컴파일된 내부 계층에 프레임워크·외부 계층 의존성이 없는지 확인한다.
    void compiledInnerLayersHaveNoFrameworkOrAdapterDependencies() throws Exception {
        for (String layer : new String[] {"domain", "application"}) {
            for (Path file : classFiles(layer)) {
                String constants = constants(file);
                for (String forbidden : FORBIDDEN) {
                    assertFalse(
                            constants.contains(forbidden),
                            file.getFileName() + " depends on " + forbidden);
                }
            }
        }
    }

    @Test
    // 금지 문자열 형식이 실제 바이트코드와 맞는다는 양성 대조: 어댑터는 OpenAPI 어노테이션을 쓴다.
    void openApiAnnotationsAreVisibleInAdapterBytecode() throws Exception {
        boolean found = false;
        for (Path file : classFiles("adapter/web")) {
            found |= constants(file).contains("io/swagger/");
        }
        assertTrue(found, "adapter/web 바이트코드에서 io/swagger/ 를 찾지 못했다");
    }
}
