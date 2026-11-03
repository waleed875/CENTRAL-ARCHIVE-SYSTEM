package com.centralArchiveSystem.Archive.common.enums;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArabicEnumLabelsTest {
    @Test void everyPublishedValueHasAnArabicLabelAndKeepsItsMachineCode() {
        var catalog = new EnumCatalog().values();
        var labels = ArabicEnumLabels.forCatalog(catalog);
        assertEquals(catalog.keySet(), labels.keySet());
        catalog.forEach((group,codes) -> {
            assertEquals(codes.size(), labels.get(group).size());
            codes.forEach(code -> {
                assertTrue(labels.get(group).containsKey(code));
                assertTrue(labels.get(group).get(code).matches(".*[\\u0600-\\u06FF].*"), group+":"+code);
            });
        });
        assertEquals("قيد المراجعة", labels.get("projectStatus").get("UNDER_REVIEW"));
        assertTrue(catalog.get("projectStatus").contains("UNDER_REVIEW"));
    }
}
