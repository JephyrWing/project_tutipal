package com.my.tutipal_back.learning.insights.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

import com.my.tutipal_back.learning.insights.repository.RawQuestion;

/**
 * PT-87 AC-8 / D5: response records of the insights feature may only carry String values in an allow list
 * (label / name / status / algorithm / reason / source). No RawQuestion, no free text, and the confusions
 * response carries no student identifier at all.
 */
class InsightsResponsePrivacyTest {

    private static final String PACKAGE = "com.my.tutipal_back.learning.insights.dto";
    private static final Set<String> ALLOWED_STRINGS = Set.of("label", "name", "status", "algorithm", "reason", "source");
    private static final Set<String> STUDENT_IDS = Set.of("userId", "userIds", "studentId", "studentIds", "members",
            "user", "users", "studentName", "name");

    private static List<Class<?>> responseRecords() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(PACKAGE)) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), InsightsResponsePrivacyTest.class.getClassLoader());
            if (type.isRecord() && !type.getSimpleName().endsWith("Request")) {
                found.add(type);
            }
        }
        return found;
    }

    private static void collect(Type type, String owner, Set<Class<?>> seen, List<String> violations,
                                Set<String> forbiddenNames) {
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] args = parameterized.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                if (args[0] == String.class) {
                    violations.add(owner + " is a collection of String");
                }
                collect(args[0], owner + "[]", seen, violations, forbiddenNames);
            } else if (Map.class.isAssignableFrom(raw)) {
                if (args[1] == String.class) {
                    violations.add(owner + " is a map of String values");
                }
                collect(args[1], owner + "{}", seen, violations, forbiddenNames);
            }
            return;
        }
        if (!(type instanceof Class<?> cls)) {
            return;
        }
        if (cls == RawQuestion.class) {
            violations.add(owner + " is RawQuestion");
            return;
        }
        if (!cls.isRecord() || !seen.add(cls)) {
            return;
        }
        for (RecordComponent component : cls.getRecordComponents()) {
            String where = cls.getSimpleName() + "." + component.getName();
            if (component.getType() == String.class && !ALLOWED_STRINGS.contains(component.getName())) {
                violations.add(where + " is a free String field");
            }
            if (forbiddenNames.contains(component.getName())) {
                violations.add(where + " identifies a student");
            }
            collect(component.getGenericType(), where, seen, violations, forbiddenNames);
        }
    }

    @Test
    void scanFindsTheResponseRecords() throws Exception {
        List<Class<?>> records = responseRecords();

        assertThat(records).contains(ConfusionsResponse.class, ConfusionsResponse.Topic.class, MasteryResponse.class,
                MasteryResponse.ConceptColumn.class, MasteryResponse.StudentRow.class, MasteryResponse.Cell.class,
                ConceptView.class, QuizConceptView.class);
    }

    @Test
    void responseRecordsOnlyHaveAllowListedStringFieldsAndNoRawQuestion() throws Exception {
        List<String> violations = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> record : responseRecords()) {
            collect(record, record.getSimpleName(), seen, violations, Set.of());
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void confusionsResponseHasNoStudentIdentifierAnywhere() {
        List<String> violations = new ArrayList<>();
        Set<String> forbidden = new HashSet<>(STUDENT_IDS);
        forbidden.remove("name");   // ConfusionsResponse has no name field either, but label is the topic name
        collect(ConfusionsResponse.class, "ConfusionsResponse", new HashSet<>(), violations, forbidden);

        assertThat(violations).isEmpty();
        assertThat(ConfusionsResponse.class.getRecordComponents()).extracting(RecordComponent::getName)
                .contains("status", "reason", "questionCount", "studentCount", "algorithm", "k", "silhouette",
                        "cached", "topics");
    }

    @Test
    void masteryResponseShapeMatchesTheDesign() {
        assertThat(MasteryResponse.class.getRecordComponents()).extracting(RecordComponent::getName)
                .containsExactly("concepts", "students");
        assertThat(MasteryResponse.ConceptColumn.class.getRecordComponents()).extracting(RecordComponent::getName)
                .containsExactly("id", "name", "quizCount", "classAvg", "belowHalf");
        assertThat(MasteryResponse.StudentRow.class.getRecordComponents()).extracting(RecordComponent::getName)
                .containsExactly("userId", "name", "cells");
        assertThat(MasteryResponse.Cell.class.getRecordComponents()).extracting(RecordComponent::getName)
                .containsExactly("conceptId", "pKnown", "observations", "lowConfidence");
    }
}
