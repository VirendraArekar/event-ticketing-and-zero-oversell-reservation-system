package com.gatekeeper.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 6;

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_EVENTS).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_EVENTS + ".DLT").partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic ticketEventsTopic() {
        return TopicBuilder.name(KafkaTopics.TICKET_EVENTS).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic ticketEventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.TICKET_EVENTS + ".DLT").partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic checkinEventsTopic() {
        return TopicBuilder.name(KafkaTopics.CHECKIN_EVENTS).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic checkinEventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.CHECKIN_EVENTS + ".DLT").partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic waitlistEventsTopic() {
        return TopicBuilder.name(KafkaTopics.WAITLIST_EVENTS).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic waitlistEventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.WAITLIST_EVENTS + ".DLT").partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic eventLifecycleEventsTopic() {
        return TopicBuilder.name(KafkaTopics.EVENT_LIFECYCLE_EVENTS).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic eventLifecycleEventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.EVENT_LIFECYCLE_EVENTS + ".DLT").partitions(PARTITIONS).replicas(1).build();
    }
}

