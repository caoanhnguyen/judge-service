package com.kma.judgeservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // 1. Khai báo TÊN CỦA BƯU CỤC (Exchange)
    public static final String JUDGE_EXCHANGE = "judge.direct.exchange";

    // 2. Khai báo tên CÁC HÒM THƯ (Queues)
    public static final String JUDGE_QUEUE = "judge.queue";
    public static final String RESULT_QUEUE = "result.queue";
    public static final String RUN_CODE_QUEUE = "judge.run.queue";
    public static final String RUN_CODE_RESULT_QUEUE = "judge.run.result.queue";

    // 3. Khai báo CÁC NHÃN ĐỊA CHỈ (Routing Keys)
    public static final String JUDGE_ROUTING_KEY = "judge.routing.key";
    public static final String RESULT_ROUTING_KEY = "result.routing.key";
    public static final String RUN_CODE_ROUTING_KEY = "judge.run.routing.key";
    public static final String RUN_CODE_RESULT_ROUTING_KEY = "judge.run.result.routing.key";

    // ==========================================
    // KHỞI TẠO CÁC BEAN (Để Judge Service tự đẻ ra Queue nếu chạy trước Core)
    // ==========================================

    @Bean
    public DirectExchange judgeExchange() {
        return new DirectExchange(JUDGE_EXCHANGE);
    }

    @Bean public Queue judgeQueue() { return new Queue(JUDGE_QUEUE, true); }
    @Bean public Queue resultQueue() { return new Queue(RESULT_QUEUE, true); }
    @Bean public Queue runCodeQueue() { return new Queue(RUN_CODE_QUEUE, true); }
    @Bean public Queue runCodeResultQueue() { return new Queue(RUN_CODE_RESULT_QUEUE, true); }

    @Bean
    public Binding resultBinding(Queue resultQueue, DirectExchange judgeExchange) {
        return BindingBuilder.bind(resultQueue).to(judgeExchange).with(RESULT_ROUTING_KEY);
    }

    @Bean
    public Binding runCodeResultBinding(Queue runCodeResultQueue, DirectExchange judgeExchange) {
        return BindingBuilder.bind(runCodeResultQueue).to(judgeExchange).with(RUN_CODE_RESULT_ROUTING_KEY);
    }

    // ==========================================
    // CẤU HÌNH CONVERTER
    // ==========================================
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
        return rabbitTemplate;
    }
}