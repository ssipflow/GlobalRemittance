package com.nelson.remittance.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // application은 선언적 트랜잭션 경계(@Transactional)와 빈 등록(@Service) 어노테이션 두 타입만 쓸 수 있다.
    // domain은 예외 없이 Spring 전체를 금지한다.
    // 상수 풀에는 어노테이션이 타입 표기 "L...;" 전체로 나타난다. 접두사만 지우면 같은 이름으로 시작하는
    // 다른 타입의 앞부분이 가려지므로 종결 ';'까지 포함해 정확히 일치할 때만 지운다.
    private static final String TRANSACTION_ANNOTATION =
            "Lorg/springframework/transaction/annotation/Transactional;";
    private static final String SERVICE_ANNOTATION = "Lorg/springframework/stereotype/Service;";
    private static final List<String> APPLICATION_ALLOWED =
            List.of(TRANSACTION_ANNOTATION, SERVICE_ANNOTATION);

    // 허용 타입 표기만 지운 뒤 금지 목록을 검사하므로 같은 spring 하위의 다른 패키지와 같은 패키지의 다른 타입은 여전히 걸린다.
    private static String withoutAllowed(String constants, List<String> allowed) {
        for (String prefix : allowed) {
            constants = constants.replace(prefix, "");
        }
        return constants;
    }

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
                if (layer.equals("application")) {
                    constants = withoutAllowed(constants, APPLICATION_ALLOWED);
                }
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

    @Test
    // 예외가 실제 바이트코드와 맞는다는 양성 대조: application은 @Transactional 접두사를 실제로 참조한다.
    void transactionAnnotationIsVisibleInApplicationBytecode() throws Exception {
        boolean found = false;
        for (Path file : classFiles("application")) {
            found |= constants(file).contains(TRANSACTION_ANNOTATION);
        }
        assertTrue(found, "application 바이트코드에서 " + TRANSACTION_ANNOTATION + " 를 찾지 못했다");
    }

    @Test
    // 예외가 실제 바이트코드와 맞는다는 양성 대조: application은 @Service 접두사도 실제로 참조한다.
    void serviceAnnotationIsVisibleInApplicationBytecode() throws Exception {
        boolean found = false;
        for (Path file : classFiles("application")) {
            found |= constants(file).contains(SERVICE_ANNOTATION);
        }
        assertTrue(found, "application 바이트코드에서 " + SERVICE_ANNOTATION + " 를 찾지 못했다");
    }

    @Test
    // 허용 접두사 제거가 다른 spring 타입·패키지까지 가리지 않는다.
    void allowedAnnotationExceptionsDoNotHideOtherSpringTypes() {
        String constants =
                "Lorg/springframework/transaction/annotation/Transactional;"
                        + "Lorg/springframework/stereotype/Service;"
                        + "Lorg/springframework/stereotype/Component;"
                        + "Lorg/springframework/stereotype/Repository;"
                        + "Lorg/springframework/transaction/annotation/EnableTransactionManagement;"
                        + "Lorg/springframework/transaction/support/TransactionTemplate;";
        String remaining = withoutAllowed(constants, APPLICATION_ALLOWED);
        assertFalse(remaining.contains(TRANSACTION_ANNOTATION));
        assertFalse(remaining.contains(SERVICE_ANNOTATION));
        assertFalse(remaining.contains("stereotype/Service;"));
        assertTrue(remaining.contains("org/springframework/stereotype/Component"));
        assertTrue(remaining.contains("org/springframework/stereotype/Repository"));
        assertTrue(
                remaining.contains(
                        "org/springframework/transaction/annotation/EnableTransactionManagement"));
        assertTrue(remaining.contains("org/springframework/transaction/support/"));
        assertTrue(remaining.contains("org/springframework/"));
    }

    @Test
    // 허용 타입 이름으로 시작하는 다른 타입은 제거되지 않아 금지 접두사에 걸린다.
    void allowedTypeNamePrefixesDoNotHideOtherTypes() {
        for (String other :
                List.of(
                        "Lorg/springframework/stereotype/ServiceLocator;",
                        "Lorg/springframework/transaction/annotation/TransactionalXxx;")) {
            assertTrue(withoutAllowed(other, APPLICATION_ALLOWED).contains("org/springframework/"));
        }
        assertEquals(
                "",
                withoutAllowed(SERVICE_ANNOTATION + TRANSACTION_ANNOTATION, APPLICATION_ALLOWED));
    }
}
