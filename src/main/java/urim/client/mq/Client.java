package urim.client.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import urim.Message;
import urim.client.task.ScriptTask;
import urim.client.task.SystemTask;
import urim.client.task.Task;

import java.util.HashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

@RestController("MqClient")
@Profile("client")
class Client {

    @Value("${name}")
    String name;
    static final String template = "%s";
    final AtomicLong counter = new AtomicLong();
    private HashMap<String, ScheduledFuture> schedulers = new HashMap<String, ScheduledFuture>();
    private HashMap<String, Long> intervals = new HashMap<String, Long>();
    private HashMap<String, Long> ttl = new HashMap<String, Long>();
    private HashMap<String, TaskTypes> types = new HashMap<String, TaskTypes>();

    private static final Logger log = LoggerFactory.getLogger(Client.class);

    @Value("${spring.rabbitmq.queue}")
    private String queueName;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public enum TaskTypes {
        system, script, ping
    }

    @RequestMapping(value = "/start", method = RequestMethod.GET)
    public Message start(@RequestParam(value="key", defaultValue="ping") String key, @RequestParam(value="interval", defaultValue="5000") String interval, @RequestParam(value="type", defaultValue="ping") String type, @RequestParam(value="name", defaultValue="ping") String name) {
        if (schedulers.containsKey(key) && intervals.get(key) != Long.parseLong(interval)) {
            stop(key, schedulers.get(key));
        }
        if (!schedulers.containsKey(key)) {
            TaskScheduler scheduler = new ConcurrentTaskScheduler();
            ScheduledFuture future = scheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (ttl.get(key) <= 0L) {
                            stop(key, schedulers.get(key));
                        } else {
                            ttl.put(key, ttl.get(key) - Long.parseLong(interval));
                        }
                        sendMessage(key, TaskTypes.valueOf(type), name, counter.incrementAndGet());
                    } catch(Exception e) {
                        log.warn("Error occurred while getting stats for " + key + ": " + e.getMessage());
                        Thread.currentThread().interrupt();
                    }
                }
            }, Long.parseLong(interval));
            schedulers.put(key, future);
            intervals.put(key, Long.parseLong(interval));
            types.put(key, TaskTypes.valueOf(type));
        }
        if (schedulers.containsKey(key)) {
            ttl.put(key, Long.parseLong(interval) * 10);
        }
        return new Message(counter.incrementAndGet(), String.format("started monitoring, %s", key), name, new Parameters(key, "start", this.name, ""));
    }

    @RequestMapping(value = "/stop", method = RequestMethod.GET)
    public Message stop(@RequestParam(value="key", defaultValue="ping") String key) {
        if (schedulers.containsKey(key)) {
            stop(key, schedulers.get(key));
        }
        return new Message(counter.incrementAndGet(), String.format("stopped monitoring, %s", key), name, new Parameters(key, "stop", this.name, ""));
    }

    protected void stop(String key, ScheduledFuture future) {
        future.cancel(false);
        schedulers.remove(key);
        intervals.remove(key);
        ttl.remove(key);
    }

    protected void sendMessage(String key, TaskTypes type, String name, long counter) throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        rabbitTemplate.convertAndSend(queueName, mapper.writeValueAsString(getStats(key, type, name, counter)));
    }

    protected Message getStats(String key, TaskTypes type, String name, long counter) {
        switch (type) {
            case system:
                return new Message(counter, new SystemTask().getStats(), this.name, new Parameters(key, "system", this.name, name));
            case script:
                return new Message(counter, new ScriptTask().getStats(name), this.name, new Parameters(key, "script", this.name, name));
            default:
                return new Message(counter, new Task().getStats(name), this.name, new Parameters(key, "default", this.name, name));
        }
    }

    @Bean
    public Queue queue() {
        HashMap<String, Object> args = new HashMap<String, Object>();
        args.put("x-message-ttl", 15000);
        return new Queue(queueName, false, false, false, args);
    }

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange("spring-boot-exchange");
    }

    @Bean
    public Binding binding(Queue queue, TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(queueName);
    }
}
