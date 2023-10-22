package com.example.server;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.observation.aop.ObservedAspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.StreamSupport;

@SpringBootApplication
public class ServerApplication {

	public static void main(String[] args) {
		Hooks.enableAutomaticContextPropagation();
		SpringApplication.run(ServerApplication.class, args);
	}

	// tag::aspect[]
	// To have the @Observed support we need to register this aspect
	@Bean
	ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
		return new ObservedAspect(observationRegistry);
	}
	// end::aspect[]

	@Bean
	HttpExchangeRepository httpExchangeRepository() {
		return new HttpExchangeRepository() {

			private final HttpExchangeRepository delegate = new InMemoryHttpExchangeRepository();
			@Override
			public List<HttpExchange> findAll() {
				return delegate.findAll();
			}

			@Override
			public void add(HttpExchange httpExchange) {
				if (!httpExchange.getRequest().getUri().getPath().contains("actuator")) {
					delegate.add(httpExchange);
				}
			}
		};
	}
}

// tag::controller[]
@RestController
class MyController {

	private static final Logger log = LoggerFactory.getLogger(MyController.class);
	private final MyUserService myUserService;

	MyController(MyUserService myUserService) {
		this.myUserService = myUserService;
	}

	@GetMapping("/user/{userId}")
	Mono<String> userName(@PathVariable("userId") String userId) {
		log.info("Got a request");
		return myUserService.userName(userId);
	}
}
// end::controller[]

// tag::service[]
@Service
class MyUserService {

	private static final Logger log = LoggerFactory.getLogger(MyUserService.class);

    @Autowired
    private UserRepository userRepository;

    public void setUserRepository(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private final Random random = new Random();

	// Example of using an annotation to observe methods
	// <user.name> will be used as a metric name
	// <getting-user-name> will be used as a span  name
	// <userType=userType2> will be set as a tag for both metric & span
	@Observed(name = "user.name",
			contextualName = "getting-user-name",
			lowCardinalityKeyValues = {"userType", "userType2"})
	Mono<String> userName(String userId) {
		return userRepository.findById(Long.valueOf(userId))
                .then(Mono.just("foo"))
				.delayElement(Duration.ofMillis(random.nextLong(200L)))
				.doOnSubscribe(ignored -> log.info("Getting user name for user with id <{}>", userId));
	}
}
// end::service[]

// tag::handler[]
// Example of plugging in a custom handler that in this case will print a statement before and after all observations take place
@Component
class MyHandler implements ObservationHandler<Observation.Context> {

	private static final Logger log = LoggerFactory.getLogger(MyHandler.class);

	@Override
	public void onStart(Observation.Context context) {
		if (log.isInfoEnabled()) {
			log.info("Before running the observation for context [{}], userType [{}]", context.getName(), getUserTypeFromContext(context));
		}
	}

	@Override
	public void onStop(Observation.Context context) {
		if (log.isInfoEnabled()) {
			log.info("After running the observation for context [{}], userType [{}]", context.getName(), getUserTypeFromContext(context));
		}
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return true;
	}

	private String getUserTypeFromContext(Observation.Context context) {
		return StreamSupport.stream(context.getLowCardinalityKeyValues().spliterator(), false)
				.filter(keyValue -> "userType".equals(keyValue.getKey()))
				.map(KeyValue::getValue)
				.findFirst()
				.orElse("UNKNOWN");
	}
}
// end::handler[]

@Table(name = "users")
class User {
	@Id
	Long id;

	String name;

	public User(Long id, String name) {
		this.id = id;
		this.name = name;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}

@Repository
interface UserRepository extends ReactiveCrudRepository<User, Long> { }