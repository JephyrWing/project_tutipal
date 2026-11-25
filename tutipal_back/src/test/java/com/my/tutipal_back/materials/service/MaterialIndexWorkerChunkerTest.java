package com.my.tutipal_back.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.materials.entity.Material;
import com.my.tutipal_back.materials.repository.MaterialRepository;

/** PT-86: the worker picks the chunker from tutipal.materials.chunker and stores it in material_chunks.chunker. */
class MaterialIndexWorkerChunkerTest {

    private static final long ID = 11L;
    private static final long SESSION = 5L;
    private static final String TEXT = "# 1장 소개\n\n" + "이것은 정렬 알고리즘의 안정성과 시간 복잡도를 함께 설명하는 문장입니다. ".repeat(3);

    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final MaterialObjectStore objects = mock(MaterialObjectStore.class);
    private final MaterialTextExtractor extractor = mock(MaterialTextExtractor.class);
    private final AiClient ai = mock(AiClient.class);
    private final AiSlots slots = mock(AiSlots.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final SessionEvents events = mock(SessionEvents.class);

    private MaterialIndexWorker worker(String chunker) {
        return new MaterialIndexWorker(materials, objects, extractor, ai, slots, jdbc, tx, events, chunker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "section-v2", "CHAR800-V1"})
    void unknownChunkerFailsAtStartup(String chunker) {
        assertThatThrownBy(() -> worker(chunker)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void charChunkerKeepsOldContentAndRecordsProvenance() {
        List<Object[]> rows = index("char800-v1");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[3]).isEqualTo(TEXT.replaceAll("[ \\t]+", " ").strip());
        assertThat(rows.getFirst()[6]).isEqualTo("char800-v1");
    }

    @Test
    void sectionChunkerPrefixesHeadingAndRecordsProvenance() {
        List<Object[]> rows = index("section-v1");

        assertThat(rows).isNotEmpty().allSatisfy(row -> {
            assertThat((String) row[3]).startsWith("[1장 소개]\n");
            assertThat(row[6]).isEqualTo("section-v1");
        });
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> index(String chunker) {
        Material material = mock(Material.class);
        when(material.getId()).thenReturn(ID);
        when(material.getSessionId()).thenReturn(SESSION);
        when(material.getFilename()).thenReturn("notes.md");
        when(material.getStorageKey()).thenReturn("key");
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(ID));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        when(materials.dueIndex(ID)).thenReturn(Optional.of(material));
        when(objects.get("key")).thenReturn(new byte[] {1});
        when(extractor.extract(eq("notes.md"), any())).thenReturn(List.of(new AiClient.Page(null, TEXT)));
        when(slots.run(any(Duration.class), any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(ai.embedWithModel(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            texts.forEach(t -> vectors.add(new float[1024]));
            return new AiClient.EmbedResponse("bge-m3", vectors);
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        worker(chunker).indexPending();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object[]>> rows = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(sql.capture(), rows.capture());
        assertThat(sql.getValue()).contains("chunker");
        return rows.getValue();
    }
}
