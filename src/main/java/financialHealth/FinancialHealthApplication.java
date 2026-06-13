package financialHealth;

import java.util.List;

import financialHealth.service.CustomerService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean; // Needed to expose the tools

@SpringBootApplication
public class FinancialHealthApplication {

	public static void main(String[] args) {
		SpringApplication.run(FinancialHealthApplication.class, args);
	}

	@Bean
	public List<ToolCallback> tools(CustomerService customerBank) {
		return List.of(ToolCallbacks.from(customerBank));
	}

}