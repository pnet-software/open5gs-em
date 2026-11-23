package gr.pnet.em;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Open5gsEmApplication {

	public static void main(String[] args) {
		SpringApplication.run(Open5gsEmApplication.class, args);
	}

}
