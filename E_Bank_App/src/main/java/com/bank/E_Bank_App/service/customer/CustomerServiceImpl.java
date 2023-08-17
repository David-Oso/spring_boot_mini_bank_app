package com.bank.E_Bank_App.service.customer;

import com.bank.E_Bank_App.config.security.jwtToken.EBankToken;
import com.bank.E_Bank_App.config.security.jwtToken.EBankTokenService;
import com.bank.E_Bank_App.config.security.services.JwtService;
import com.bank.E_Bank_App.data.model.*;
import com.bank.E_Bank_App.data.repository.CustomerRepository;
import com.bank.E_Bank_App.dto.request.*;
import com.bank.E_Bank_App.dto.request.mailRequest.EmailRequest;
import com.bank.E_Bank_App.dto.response.*;
import com.bank.E_Bank_App.exception.E_BankException;
import com.bank.E_Bank_App.exception.InvalidDetailsException;
import com.bank.E_Bank_App.exception.NotFoundException;
import com.bank.E_Bank_App.otp.OtpEntity;
import com.bank.E_Bank_App.service.mail.MailService;
import com.bank.E_Bank_App.otp.OtpService;
import com.bank.E_Bank_App.service.cloud.CloudService;
import com.bank.E_Bank_App.utils.E_BankUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@AllArgsConstructor
public class CustomerServiceImpl implements CustomerService {
    private final CustomerRepository customerRepository;
    private final MailService mailService;
    private final OtpService otpService;
    private final ModelMapper modelMapper;
    private final CloudService cloudService;
    private EmailRequest emailRequest;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EBankTokenService eBankTokenService;
    private final AuthenticationManager authenticationManager;
    @Override
    public RegisterResponse register(RegisterRequest registerRequest) {
        checkIfEmailAlreadyExists(registerRequest.getEmail());
        Customer customer = new Customer();
        AppUser appUser = getNewAppUser(registerRequest);
        Customer savedCustomer = getNewCustomer(registerRequest, customer, appUser);
        String otp = otpService.generateAndSaveOtp(savedCustomer);
//        log.info("\n\n:::::::::::::::::::: GENERATED OTP -> %s ::::::::::::::::::::\n".formatted(otp));
        sendVerificationMail(savedCustomer, otp);
        return RegisterResponse.builder()
                .message("Check your mail for otp to activate your account")
                .isSuccess(true)
                .build();
    }

    private void checkIfEmailAlreadyExists(String email) {
        boolean isPresent = customerRepository.findByAppUser_Email(email).isPresent();
        if(isPresent){
            Customer customer = customerRepository.findByAppUser_Email(email).get();
            AppUser appUser = customer.getAppUser();
            if(!appUser.isEnable()) resendVerificationMail(customer.getId());
            else if (appUser.isLocked())
                throw new E_BankException("Account has been blocked");
        }
    }

    private AppUser getNewAppUser(RegisterRequest registerRequest) {
        AppUser appUser = modelMapper.map(registerRequest, AppUser.class);
        appUser.setRole(Role.CUSTOMER);
        String encodedPassword = passwordEncoder.encode(registerRequest.getPassword());
        appUser.setPassword(encodedPassword);
        return appUser;
    }

    private Customer getNewCustomer(RegisterRequest registerRequest, Customer customer, AppUser appUser) {
        customer.setAppUser(appUser);
        LocalDate dateOfBirth = convertDateOBirthToLocalDate(registerRequest.getDateOfBirth());
        customer.setDateOfBirth(dateOfBirth);
        int age = changeDateToIntAndValidateAge(customer.getDateOfBirth());
        customer.setAge(age);
        customer.setGender(registerRequest.getGender());
        return customerRepository.save(customer);
    }

    private LocalDate convertDateOBirthToLocalDate(String date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return LocalDate.parse(date, formatter);
    }
    private int changeDateToIntAndValidateAge(LocalDate date) {
        return Period.between(date, LocalDate.now()).getYears();
    }

    private void sendVerificationMail(Customer customer, String otp){
        String mailTemplate = E_BankUtils.GET_EMAIL_VERIFICATION_MAIL_TEMPLATE;
        String firstName = customer.getAppUser().getFirstName();
        String htmlContent = String.format(mailTemplate, firstName, otp);
        String subject = "Email Verification";
        String email = customer.getAppUser().getEmail();
        emailRequest = buildEmailRequest(email, subject, htmlContent);
        mailService.sendHtmlMail(emailRequest);

    }
    @Override
    public OtpVerificationResponse verifyEmail(String otp) {
        OtpEntity otpEntity = otpService.validateReceivedOtp(otp);
        Customer customer = otpEntity.getCustomer();
        AppUser appUser = customer.getAppUser();
        if(appUser.isEnable())
            throw new E_BankException("User is already enabled");
        if(!appUser.isLocked()){
            appUser.setEnable(true);
            Customer savedCustomer = customerRepository.save(customer);
            otpService.deleteToken(otpEntity);
            return getOtpVerificationResponse(savedCustomer);
        }
        throw new E_BankException("Error verifying email");
    }

    private OtpVerificationResponse getOtpVerificationResponse(Customer customer) {
        return OtpVerificationResponse.builder()
                .id(customer.getId())
                .firstName(customer.getAppUser().getFirstName())
                .lastName(customer.getAppUser().getLastName())
                .age(customer.getAge())
                .gender(customer.getGender())
                .email(customer.getAppUser().getEmail())
                .phoneNumber(customer.getAppUser().getPhoneNumber())
                .jwtResponse(getJwtTokenResponse(customer.getAppUser()))
                .build();
    }

    private JwtResponse getJwtTokenResponse(AppUser user){
        final String email = user.getEmail();
        final String accessToken = jwtService.generateAccessToken(email);
        final String refreshToken = jwtService.generateRefreshToken(email);
        saveEBankToken(user, accessToken);
        return JwtResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    private void saveEBankToken(AppUser user, String accessToken) {
        final EBankToken eBankToken = EBankToken.builder()
                .token(accessToken)
                .appUser(user)
                .isExpired(false)
                .isRevoked(false)
                .build();
        eBankTokenService.saveToken(eBankToken);
    }

    @Override
    public String resendVerificationMail(Long customerId) {
        Customer customer = getCustomerById(customerId);
        String otp = otpService.generateAndSaveOtp(customer);
        sendVerificationMail(customer, otp);
        return "Another otp has sent to your mail proceed by checking your email";
    }


    @Override
    public LoginResponse login(LoginRequest loginRequest) {
        Customer customer = authenticateUser(loginRequest.getEmail(), loginRequest.getPassword());
        JwtResponse jwtResponse = getJwtTokenResponse(customer.getAppUser());
        return LoginResponse.builder()
                .jwtResponse(jwtResponse)
                .build();
    }

    private Customer authenticateUser(String email, String password) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));
        String userEmail = authentication.getPrincipal().toString();
        return getCustomerByEmail(userEmail);
    }

    @Override
    public Customer getCustomerById(Long customerId) {
        return customerRepository.findByAppUser_Id(customerId).orElseThrow(
                ()-> new NotFoundException("Customer not found"));
    }

    @Override
    public Customer getCustomerByEmail(String email) {
        return customerRepository.findByAppUser_Email(email).orElseThrow(
                ()-> new NotFoundException("Customer with email %s not found".formatted(email)));
    }

    @Override
    public Customer getCustomerByAccountNumber(String accountNumber) {
        return customerRepository.findByAccount_AccountNumber(accountNumber).orElseThrow(
                ()-> new NotFoundException(String.format("Customer with account number %s not found", accountNumber)));
    }

    @Override
    public String setUpAccount(SetUpAccountRequest setUpAccountRequest) {
        Customer customer = getCustomerById(setUpAccountRequest.getCustomerId());
        String firstName = customer.getAppUser().getFirstName();
        String lastName = customer.getAppUser().getLastName();
        Account account = customer.getAccount();
        String accountName = "%s %s"
                .formatted(firstName, lastName);
        String accountNumber = generateAccountNumber();
        account.setAccountName(accountName);
        account.setAccountNumber(accountNumber);
        account.setPin(setUpAccountRequest.getPin());
        customerRepository.save(customer);
        return "Account set up successful";
    }


    private String generateAccountNumber() {
        SecureRandom randomNumbers = new SecureRandom();
        return randomNumbers.ints(10, 0, 10)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining());
    }

    @Override
    public String makeDeposit(DepositRequest depositRequest) {
        Customer customer = getCustomerById(depositRequest.getCustomerId());
        Account account = customer.getAccount();
        Transaction transaction = setTransaction(depositRequest.getAmount(), TransactionType.DEPOSIT);
        account.getTransactions().add(transaction);
        sendDepositNotification(customer, depositRequest.getAmount());
        customerRepository.save(customer);
        return "Transaction Successful";
    }

    private void sendDepositNotification(Customer customer, BigDecimal amount) {
        String mailTemplate = E_BankUtils.GET_DEPOSIT_NOTIFICATION_MAIL_TEMPLATE;
        String email = customer.getAppUser().getEmail();
        String firstName = customer.getAppUser().getFirstName();
//        String fromCustomerFirstName = fromCustomer.getAppUser().getFirstName();
//        String fromCustomerLastName = fromCustomer.getAppUser().getLastName();
        String lastName= customer.getAppUser().getLastName();
        String accountName = "%s %s".formatted(firstName, lastName);
        StringBuilder number = new StringBuilder(customer.getAccount().getAccountNumber());
        String accountNumber = number.replace(2, 8, "********").toString();
        String transactionType = "Deposit";
        String description = "Deposit into your account";
        String transactionAmount = "₦%s".formatted(amount);
        String transactionDateAndTime = DateTimeFormatter.ofPattern("EEE, dd/MM/yy, hh:mm:ss a").format(LocalDateTime.now());
        String currentBalance = "₦%s".formatted(calculateBalance(customer.getId()));
        String myPhoneNumber = E_BankUtils.BANK_PHONE_NUMBER;
        String myEmail = "osodavid001@gmail.com";
        String subject = "Credit Alert Notification";
        String htmlContent = String.format(mailTemplate, firstName, accountName, accountNumber, transactionType,
                description, transactionAmount, transactionDateAndTime, currentBalance, myPhoneNumber, myEmail);
        emailRequest = buildEmailRequest(email, subject, htmlContent);
        mailService.sendHtmlMail(emailRequest);
    }

    @Override
    public String makeWithdraw(WithDrawRequest withDrawRequest) {
        Customer customer = getCustomerById(withDrawRequest.getCustomerId());
        Account account = customer.getAccount();
        String pin = account.getPin();
        validatePin(pin, withDrawRequest.getPin());
        BigDecimal balance = calculateBalance(withDrawRequest.getCustomerId());
        checkWhetherBalanceIsSufficient(balance, withDrawRequest.getAmount());
        Transaction transaction = setTransaction(withDrawRequest.getAmount(), TransactionType.WITHDRAW);
        account.getTransactions().add(transaction);
        sendWithdrawNotificationMail(customer, withDrawRequest.getAmount());
        customerRepository.save(customer);
        return "Transaction Successful";
    }

    private void sendWithdrawNotificationMail(Customer customer, BigDecimal amount) {
        String mailTemplate = E_BankUtils.GET_WITHDRAW_NOTIFICATION_MAIL_TEMPLATE;
        String email = customer.getAppUser().getEmail();
        String firstName = customer.getAppUser().getFirstName();
        String lastName = customer.getAppUser().getLastName();
        String accountName = "%s %s".formatted(firstName, lastName);
        StringBuilder number = new StringBuilder(customer.getAccount().getAccountNumber());
        String accountNumber = number.replace(2, 8, "********").toString();
        String transactionType = "Withdraw";
        String description = "Withdrawal from bank";
        String transactionAmount = "₦%s".formatted(amount);
        String transactionDateAndTime = DateTimeFormatter.ofPattern("EEE, dd/MM/yy, hh:mm:ss a").format(LocalDateTime.now());
        String currentBalance = "₦%s".formatted(calculateBalance(customer.getId()));
        String myPhoneNumber = E_BankUtils.BANK_PHONE_NUMBER;
        String myEmail = "osodavid001@gmail.com";
        String subject = "Debit Alert Notification";
        String htmlContent = String.format(mailTemplate, firstName, accountName, accountNumber, transactionType,
                description, transactionAmount, transactionDateAndTime, currentBalance, myPhoneNumber, myEmail);
        emailRequest = buildEmailRequest(email, subject, htmlContent);
        mailService.sendHtmlMail(emailRequest);
    }

    private static void validatePin(String pin, String requestPin) {
        if (pin == null)
            throw new E_BankException("Input your pin");
        else if(!pin.equals(requestPin))
            throw new InvalidDetailsException("Incorrect pin");
    }
    private static void checkWhetherBalanceIsSufficient(BigDecimal balance, BigDecimal requestAmount){
        if(requestAmount.compareTo(balance) > 0)
            throw new E_BankException("Insufficient balance");
    }

    @Override
    public String makeTransfer(TransferRequest transferRequest) {
        Customer customer = getCustomerById(transferRequest.getCustomerId());
        Account account = customer.getAccount();
        String pin = account.getPin();
        validatePin(pin, transferRequest.getPin());
        BigDecimal balance = calculateBalance(transferRequest.getCustomerId());
        checkWhetherBalanceIsSufficient(balance, transferRequest.getAmount());
        Customer recipient = getCustomerByAccountNumber(transferRequest.getRecipientAccountNumber());
        Account recipientAccount = recipient.getAccount();

        Transaction transaction = setTransaction(transferRequest.getAmount(), TransactionType.TRANSFER);
        account.getTransactions().add(transaction);
        customerRepository.save(customer);

        transaction.setTransactionType(TransactionType.DEPOSIT);
        recipientAccount.getTransactions().add(transaction);
        sendTransferNotificationMail(customer, transferRequest.getAmount(), recipientAccount.getAccountNumber());
        sendDepositNotification(recipient, transferRequest.getAmount());
        customerRepository.save(recipient);
        return "Transaction Successful";
    }

    private void sendTransferNotificationMail(Customer customer, BigDecimal amount, String toAccountNumber) {
        String mailTemplate = E_BankUtils.GET_TRANSFER_NOTIFICATION_MAIL_TEMPLATE;
        String firstName = customer.getAppUser().getFirstName();
        String email = customer.getAppUser().getEmail();
        String lastName = customer.getAppUser().getLastName();
        String accountName = "%s %s".formatted(firstName, lastName);
        StringBuilder number = new StringBuilder(customer.getAccount().getAccountNumber());
        String accountNumber = number.replace(2, 8, "********").toString();
        String recipientAccountNumber = new StringBuilder(toAccountNumber).replace(2, 8, "********").toString();
        String transactionType = "Transfer";
        String description = "Transfer to account number %s".formatted(toAccountNumber);
        String transactionAmount = "₦%s".formatted(amount);
        String transactionDateAndTime = DateTimeFormatter.ofPattern("EEE, dd/MM/yy, hh:mm:ss a").format(LocalDateTime.now());
        String currentBalance = "₦%s".formatted(calculateBalance(customer.getId()));
        String myPhoneNumber = E_BankUtils.BANK_PHONE_NUMBER;
        String myEmail = "osodavid001@gmail.com";
        String subject = "Transfer Transaction Notification";
        String htmlContent = String.format(mailTemplate, firstName, accountName, accountNumber, recipientAccountNumber, description,
                transactionType, transactionAmount, transactionDateAndTime, currentBalance, myPhoneNumber, myEmail);
        emailRequest = buildEmailRequest(email, subject, htmlContent);
        mailService.sendHtmlMail(emailRequest);
    }

    private static Transaction setTransaction(BigDecimal amount, TransactionType transactionType){
        Transaction transaction = new Transaction();
        transaction.setAmount(amount);
        transaction.setTransactionType(transactionType);
        transaction.setTransactionTime(LocalDateTime.now());
        return transaction;
    }

    @Override
    public BigDecimal getBalance(Long customerId, String pin) {
        if (customerId != null){
            Customer customer = getCustomerById(customerId);
            String accountPin = customer.getAccount().getPin();
            if(!accountPin.equals(pin))
                throw new InvalidDetailsException("Incorrect pin");
            else return calculateBalance(customerId);
        }
        throw new E_BankException("field customer id cannot be null");
    }

    @Override
    public UpdateCustomerResponse updateCustomer(UpdateCustomerRequest updateCustomerRequest) {
        Customer customer = getCustomerById(updateCustomerRequest.getUserId());
        AppUser appUser = customer.getAppUser();
        appUser.setFirstName(updateCustomerRequest.getFirstName());
        appUser.setLastName(updateCustomerRequest.getLastName());
        customer.setGender(updateCustomerRequest.getGender());
        LocalDate dateOfBirth = convertDateOBirthToLocalDate(updateCustomerRequest.getDateOfBirth());
        int age = changeDateToIntAndValidateAge(dateOfBirth);
        customer.setDateOfBirth(dateOfBirth);
        customer.setAge(age);
        customer.setUpdatedAt(LocalDateTime.now());
        Customer savedCustomer = customerRepository.save(customer);
        return getUpdateCustomerResponse(savedCustomer);
    }

    private static UpdateCustomerResponse getUpdateCustomerResponse(Customer savedCustomer) {
        return UpdateCustomerResponse.builder()
                .id(savedCustomer.getId())
                .lastName(savedCustomer.getAppUser().getLastName())
                .firstName(savedCustomer.getAppUser().getFirstName())
                .phoneNumber(savedCustomer.getAppUser().getPhoneNumber())
                .email(savedCustomer.getAppUser().getEmail())
                .age(savedCustomer.getAge())
                .gender(savedCustomer.getGender())
                .build();
    }

    @Override
    public String changePassword(ChangePasswordRequest changePasswordRequest) {
        Customer customer = getCustomerById(changePasswordRequest.getUserId());
        AppUser appUser = customer.getAppUser();
        Customer authenticatedCustomer =
                authenticateUser(appUser.getEmail(), changePasswordRequest.getPassword());
        String encodedPassword = passwordEncoder.encode(changePasswordRequest.getNewPassword());
        authenticatedCustomer.getAppUser()
                .setPassword(encodedPassword);
        customerRepository.save(authenticatedCustomer);
        return "Customer password updated successfully";
    }

    private BigDecimal calculateBalance(Long customerId){
        Customer customer = getCustomerById(customerId);
        BigDecimal balance = BigDecimal.ZERO;
        List<Transaction> transactions = customer.getAccount().getTransactions();
        for(Transaction transaction : transactions){
            if(transaction.getTransactionType() == TransactionType.DEPOSIT)
                balance = balance.add(transaction.getAmount());
            if(transaction.getTransactionType() == TransactionType.WITHDRAW ||
                    transaction.getTransactionType() == TransactionType.TRANSFER)
                balance = balance.subtract(transaction.getAmount());
        }
        return balance;
    }

    @Override
    public String sendResetPasswordMail(Long customerId) {
        Customer customer = getCustomerById(customerId);
        String mailTemplate = E_BankUtils.GET_RESET_PASSWORD_MAIL_TEMPLATE;
        String firstName = customer.getAppUser().getFirstName();
        String otp = otpService.generateAndSaveOtp(customer);
        String htmlContent = String.format(mailTemplate, firstName, otp, E_BankUtils.BANK_PHONE_NUMBER);
        String subject = "Reset Password";
        String email = customer.getAppUser().getEmail();
        emailRequest = buildEmailRequest(email, subject, htmlContent);
        mailService.sendHtmlMail(emailRequest);
        return "Check your email to reset your password";
    }

    @Override
    public String resetPassword(ResetPasswordRequest resetPasswordRequest) {
        Customer customer = getCustomerByEmail(resetPasswordRequest.getEmail());
        AppUser appUser = customer.getAppUser();
        OtpEntity otpEntity = otpService.validateReceivedOtp(resetPasswordRequest.getToken());
        appUser.setPassword(resetPasswordRequest.getNewPassword());
        if(!appUser.getPassword().equals(resetPasswordRequest.getConfirmPassword()))
            throw new InvalidDetailsException("Password doesn't match");
        customerRepository.save(customer);
        otpService.deleteToken(otpEntity);
        return "Password reset successful";
    }

    @Override
    public String uploadImage(UploadImageRequest uploadImageRequest) {
        Customer customer= getCustomerById(uploadImageRequest.getCustomerId());
        String imageUrl = cloudService.upload(uploadImageRequest.getProfileImage());
        customer.setImageUrl(imageUrl);
        customer.setUpdatedAt(LocalDateTime.now());
        customerRepository.save(customer);
        return "Profile image uploaded";
    }

    @Override
    public Transaction getTransactionByCustomerIdAndTransactionId(Long customerId, Long transactionId) {
        Customer customer = getCustomerById(customerId);
        List<Transaction> transactions = customer.getAccount().getTransactions();
        for(Transaction transaction : transactions){
            if (transaction.getId().equals(transactionId))
                return transaction;
        }
        throw new E_BankException("Transaction not found");
    }

    @Override
    public List<Transaction> getAllTransactionsByCustomerId(Long customerId) {
        Customer customer = getCustomerById(customerId);
        return customer.getAccount().getTransactions();
    }

    @Override
    public String deleteTransactionByCustomerIdAndTransactionId(Long customerId, Long transactionId) {
        Customer customer = getCustomerById(customerId);
        List<Transaction> transactions = customer.getAccount().getTransactions();
        transactions.removeIf(transaction ->
                transaction.getId().equals(transactionId));
        return "Transaction deleted";
    }

    @Override
    public String deleteAllTransactionsByCustomerId(Long customerId) {
        Customer customer = getCustomerById(customerId);
        List<Transaction> transactions = customer.getAccount().getTransactions();
        transactions.clear();
        return "Transactions deleted successfully";
    }

    private EmailRequest buildEmailRequest(String email, String subject, String htmlContent){
        emailRequest = new EmailRequest();
        emailRequest.setRecipientEmail(email);
        emailRequest.setSubject(subject);
        emailRequest.setHtmlContent(htmlContent);
        return emailRequest;
    }
}
