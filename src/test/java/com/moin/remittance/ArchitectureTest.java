package com.moin.remittance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    @Test
    void compiledInnerLayersHaveNoFrameworkOrAdapterDependencies() throws Exception {
        for (String layer : new String[] {"domain", "application"}) {
            Path directory = Path.of("build/classes/java/main/com/moin/remittance", layer);
            assertTrue(Files.isDirectory(directory));
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String constants =
                            new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    for (String forbidden :
                            new String[] {
                                "org/springframework/",
                                "jakarta/persistence/",
                                "com/moin/remittance/adapter/"
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
