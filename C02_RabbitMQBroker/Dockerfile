# Start from the official RabbitMQ image with management plugin
FROM rabbitmq:3.13-management

# Enable desired RabbitMQ plugins (offline before server starts)
RUN rabbitmq-plugins enable --offline rabbitmq_jms_topic_exchange