package com.logistics.order.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE = "logistics.queue";
    public static final String EXCHANGE = "logistics.exchange";
    public static final String ROUTING_KEY = "logistics.create";

    @Bean
    public Queue logisticsQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public DirectExchange logisticsExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Binding binding(Queue logisticsQueue, DirectExchange logisticsExchange) {
        return BindingBuilder.bind(logisticsQueue).to(logisticsExchange).with(ROUTING_KEY);
    }
}
