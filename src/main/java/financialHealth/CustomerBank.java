package financialHealth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class CustomerBank {

    public record Customer(String name, String accountNumber, String phoneNumber, double balance) {
    }

    private final Map<String, Customer> customers = new ConcurrentHashMap<>();

    @Tool(name = "addCustomer",
            description = "Add a new customer or update an existing one. Specify name, account number, phone number and initial balance.")
    public String addCustomer(String name, String accountNumber, String phoneNumber, double balance) {
        if (name == null || name.trim().isEmpty()
                || accountNumber == null || accountNumber.trim().isEmpty()) {
            return "Error: Invalid name or account number.";
        }
        customers.put(accountNumber, new Customer(name, accountNumber, phoneNumber, balance));
        return "Added customer " + name + " with account number " + accountNumber + " and balance " + balance + ".";
    }

    @Tool(name = "getCustomers",
            description = "Get all customers currently stored. Returns a list of customers with their name, account number, phone number and balance.")
    public List<Customer> getCustomers() {
        return new ArrayList<>(customers.values());
    }

    @Tool(name = "getCustomerByAccount",
            description = "Get a single customer's details by account number.")
    public Customer getCustomerByAccount(String accountNumber) {
        return customers.get(accountNumber);
    }

    @Tool(name = "updateBalance",
            description = "Deposit or withdraw an amount from a customer's balance. Specify account number and amount (negative for withdrawal).")
    public String updateBalance(String accountNumber, double amount) {
        Customer customer = customers.get(accountNumber);
        if (customer == null) {
            return "Error: CustomerBank with account number '" + accountNumber + "' not found.";
        }
        double newBalance = customer.balance() + amount;
        if (newBalance < 0) {
            return "Error: Insufficient balance. Current balance is " + customer.balance() + ".";
        }
        customers.put(accountNumber, new Customer(customer.name(), customer.accountNumber(), customer.phoneNumber(), newBalance));
        return "Updated balance for " + customer.name() + ". New balance: " + newBalance + ".";
    }

    @Tool(name = "removeCustomer",
            description = "Remove a customer from the records by account number.")
    public String removeCustomer(String accountNumber) {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return "Error: Invalid account number.";
        }
        Customer removed = customers.remove(accountNumber);
        if (removed == null) {
            return "Error: CustomerBank with account number '" + accountNumber + "' not found.";
        }
        return "Removed customer '" + removed.name() + "' with account number " + accountNumber + ".";
    }
}