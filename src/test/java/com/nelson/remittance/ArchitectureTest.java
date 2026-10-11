package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

// 바이트코드 기반으로 클린 아키텍처 의존성 방향을 검증한다.
class ArchitectureTest {
    @Test
    // 컴파일된 내부 계층에 프레임워크·외부 계층 의존성이 없는지 확인한다.
    void compiledInnerLayersHaveNoFrameworkOrAdapterDependencies() throws Exception {
        for (String layer : new String[] {"domain", "application"}) {
            Path directory = Path.of("build/classes/java/main/com/nelson/remittance", layer);
            assertTrue(Files.isDirectory(directory));
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String constants =
                            new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    for (String forbidden :
                            new String[] {
                                "org/springframework/",
                                "jakarta/persistence/",
                                "com/nelson/remittance/adapter/"
                            }) {
                        assertFalse(
                                constants.contains(forbidden),
                                file.getFileName() + " depends on " + forbidden);
                    }
                }
            }
        }
    }
}
