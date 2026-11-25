package com.my.tutipal_back.learning.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Component;

import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.tutor.service.ConversationMemory;
import com.my.tutipal_back.learning.tutor.service.ConversationMemoryConfig;

/** PT-106 AC-4: exactly one ConversationMemory bean and it is StoredConversationMemory (the default steps aside). */
class StoredConversationMemoryBeanTest {

    @Test
    void storedMemoryIsAComponentSoComponentScanRegistersIt() {
        assertThat(StoredConversationMemory.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(ConversationMemory.class).isAssignableFrom(StoredConversationMemory.class);
    }

    @Test
    void exactlyOneConversationMemoryBeanAndItIsTheStoredOne() {
        new ApplicationContextRunner()
                .withBean(ConversationStore.class, FakeConversationStore::new)
                .withBean(ConversationBinding.class)
                .withBean(StoredConversationMemory.class)
                .withUserConfiguration(ConversationMemoryConfig.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ConversationMemory.class)
                        .getBean(ConversationMemory.class).isInstanceOf(StoredConversationMemory.class));
    }
}
