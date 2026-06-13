package financialHealth.service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    public record Customer(String name, String accountNumber, String phoneNumber, double balance) {
    }

    public record Transaction(String date, String narration, double withdrawalAmt, double depositAmt, double closingBalance) {
    }

    private final Map<String, Customer> customers = new ConcurrentHashMap<>();
    private final Map<String, List<Transaction>> transactions = new ConcurrentHashMap<>();

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
            return "Error: CustomerService with account number '" + accountNumber + "' not found.";
        }
        double newBalance = customer.balance() + amount;
        if (newBalance < 0) {
            return "Error: Insufficient balance. Current balance is " + customer.balance() + ".";
        }
        customers.put(accountNumber, new Customer(customer.name(), customer.accountNumber(), customer.phoneNumber(), newBalance));
        return "Updated balance for " + customer.name() + ". New balance: " + newBalance + ".";
    }

    @Tool(name = "addCustomerFromStatement",
            description = "Read a bank account statement PDF from the given file path, extract the customer's name, account number, phone number and current balance, and add/update the customer record accordingly.")
    public String addCustomerFromStatement(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                return "Error: File not found at path '" + filePath + "'.";
            }

            String text;

            // Support PDF and Excel files
            if (isPdf(file)) {
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
                        new FileSystemResource(file),
                        PdfDocumentReaderConfig.defaultConfig());
                List<Document> documents = pdfReader.get();
                text = documents.stream()
                        .map(Document::getText)
                        .collect(Collectors.joining("\n"));
            } else if (isExcel(file)) {
                text = readExcel(file);
            } else {
                return "Error: Unsupported file format. Please provide a PDF or Excel file.";
            }

            String name = extract(text, "(?i)(?:Customer Name|Account Holder|Name)\\s*[:\\-]\\s*(.+)");
            String accountNumber = extract(text, "(?i)(?:Account Number|A/c No\\.?|Account No\\.?)\\s*[:\\-]\\s*([A-Za-z0-9]+)");
            String phoneNumber = extract(text, "(?i)(?:Phone|Mobile|Contact No\\.?)\\s*[:\\-]\\s*(\\+?[0-9\\- ]{8,15})");
            String balanceStr = extract(text, "(?i)(?:Available Balance|Current Balance|Closing Balance|Balance)\\s*[:\\-]?\\s*(?:Rs\\.?|INR|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");

            if (name == null || accountNumber == null) {
                return "Error: Could not extract required customer details (name/account number) from the statement.";
            }

            double balance = 0.0;
            if (balanceStr != null) {
                balance = Double.parseDouble(balanceStr.replace(",", "").trim());
            }
            if (phoneNumber == null) {
                phoneNumber = "";
            }

            customers.put(accountNumber, new Customer(name.trim(), accountNumber.trim(), phoneNumber.trim(), balance));

            List<Transaction> txList = extractTransactions(text);
            transactions.put(accountNumber.trim(), txList);

            return "Added customer " + name.trim() + " with account number " + accountNumber.trim()
                    + ", phone " + phoneNumber.trim() + ", balance " + balance
                    + " and " + txList.size() + " transactions (extracted from statement).";
        } catch (Exception e) {
            return "Error: Failed to process the statement PDF - " + e.getMessage();
        }
    }

    @Tool(name = "getTransactions",
            description = "Get the list of transactions (date, narration, withdrawal amount, deposit amount, closing balance) extracted from a customer's statement, by account number.")
    public List<Transaction> getTransactions(String accountNumber) {
        return transactions.getOrDefault(accountNumber, new ArrayList<>());
    }

    private List<Transaction> extractTransactions(String text) {
        List<Transaction> result = new ArrayList<>();
        // Matches lines like: dd/mm/yy <narration ...> <ref no> dd/mm/yy <withdrawal> <deposit> <closing balance>
        // Withdrawal or deposit amount may be absent.
        Pattern rowPattern = Pattern.compile(
                "(\\d{2}/\\d{2}/\\d{2,4})\\s+(.+?)\\s+([A-Za-z0-9]+)\\s+\\d{2}/\\d{2}/\\d{2,4}\\s+"
                        + "(?:([0-9,]+\\.[0-9]{2})\\s+)?(?:([0-9,]+\\.[0-9]{2})\\s+)?([0-9,]+\\.[0-9]{2})");
        Matcher matcher = rowPattern.matcher(text);
        while (matcher.find()) {
            String date = matcher.group(1);
            String narration = matcher.group(2).trim();
            String withdrawalStr = matcher.group(4);
            String depositStr = matcher.group(5);
            String closingStr = matcher.group(6);

            double withdrawal = withdrawalStr != null ? Double.parseDouble(withdrawalStr.replace(",", "")) : 0.0;
            double deposit = depositStr != null ? Double.parseDouble(depositStr.replace(",", "")) : 0.0;
            double closing = Double.parseDouble(closingStr.replace(",", ""));

            result.add(new Transaction(date, narration, withdrawal, deposit, closing));
        }
        return result;
    }

    private String extract(String text, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    // Simple PDF detection: extension, MIME probe and PDF magic header
    private boolean isPdf(File file) {
        String name = file.getName();
        if (name != null && name.toLowerCase().endsWith(".pdf")) {
            return true;
        }
        Path path = file.toPath();
        try {
            String type = Files.probeContentType(path);
            if (type != null && type.toLowerCase().contains("pdf")) {
                return true;
            }
        } catch (IOException e) {
            // ignore
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] header = new byte[4];
            int r = in.read(header);
            if (r == 4) {
                String h = new String(header, StandardCharsets.US_ASCII);
                if ("%PDF".equals(h)) {
                    return true;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    // Detect Excel files by extension or content type
    private boolean isExcel(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
            return true;
        }
        try {
            String type = Files.probeContentType(file.toPath());
            if (type != null && (type.toLowerCase().contains("excel") || type.toLowerCase().contains("spreadsheet") || type.toLowerCase().contains("officedocument.spreadsheet"))) {
                return true;
            }
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    // Read an Excel file and produce a plain-text representation (sheet,row,cell)
    private String readExcel(File file) {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook wb = WorkbookFactory.create(fis)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);

                sb.append("Sheet:").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    boolean firstCell = true;
                    for (Cell cell : row) {
                        if (!firstCell) sb.append('\t');
                        firstCell = false;
                        switch (cell.getCellType()) {
                            case STRING:
                                sb.append(cell.getStringCellValue());
                                break;
                            case NUMERIC:
                                sb.append(cell.getNumericCellValue());
                                break;
                            case BOOLEAN:
                                sb.append(cell.getBooleanCellValue());
                                break;
                            case FORMULA:
                                try {
                                    sb.append(cell.getStringCellValue());
                                } catch (Exception ex) {
                                    sb.append(cell.getCellFormula());
                                }
                                break;
                            case BLANK:
                                break;
                            default:
                                try {
                                    sb.append(cell.toString());
                                } catch (Exception ex) {
                                    // ignore
                                }
                        }
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }
        } catch (Exception e) {
            // return empty text on failure (caller will handle missing fields)
            return "";
        }
        return sb.toString();
    }

    @Tool(name = "removeCustomer",
            description = "Remove a customer from the records by account number.")
    public String removeCustomer(String accountNumber) {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return "Error: Invalid account number.";
        }
        Customer removed = customers.remove(accountNumber);
        if (removed == null) {
            return "Error: CustomerService with account number '" + accountNumber + "' not found.";
        }
        return "Removed customer '" + removed.name() + "' with account number " + accountNumber + ".";
    }
}