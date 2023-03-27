package com.lucasit.withdraw.rest.service.impl;

import com.lucasit.withdraw.exception.BusinessException;
import com.lucasit.withdraw.exception.ExternalException;
import com.lucasit.withdraw.mapper.TransactionMapper;
import com.lucasit.withdraw.mapper.WalletMapper;
import com.lucasit.withdraw.model.Account;
import com.lucasit.withdraw.model.OperationType;
import com.lucasit.withdraw.model.Transactions;
import com.lucasit.withdraw.model.TrasactionStatus;
import com.lucasit.withdraw.operations.OperationTypeStrategy;
import com.lucasit.withdraw.repository.AccountRepository;
import com.lucasit.withdraw.repository.OperationTypeRepository;
import com.lucasit.withdraw.repository.TransactionRepository;
import com.lucasit.withdraw.request.external.ExternalTransactionBalanceResponseBody;
import com.lucasit.withdraw.request.external.ExternalTransactionRequestBody;
import com.lucasit.withdraw.request.external.ExternalTransactionResponseBody;
import com.lucasit.withdraw.request.internal.InternalTransactionResponseBody;
import com.lucasit.withdraw.request.internal.WalletRequestBody;
import com.lucasit.withdraw.rest.RestCaller;
import com.lucasit.withdraw.rest.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final Integer TEN_PERCENT = 10;

    @Value("${uri.wallet.transaction}")
    private String uriPayment;

    @Value("${uri.wallet.balance}")
    private String uriBalance;

    private final TransactionRepository transactionRepository;
    private final OperationTypeRepository operationTypeRepository;
    private final AccountRepository accountRepository;
    private final RestCaller restCaller;


    @Transactional
    public InternalTransactionResponseBody transaction(WalletRequestBody walletRequestBody) {

        var operationType = operationTypeRepository.findById(walletRequestBody.getOperationType())
                .orElseThrow(() -> new BusinessException("Operation type not found."));

        Account account = accountRepository.findAccountByAccountNumber(walletRequestBody.getWalletAccountNumber())
                .orElseThrow(() -> new BusinessException("Account not found."));

        /*Operations*/

        OperationTypeStrategy operationTypeStrategy = OperationType.OperationType(operationType.getId());

        BigDecimal newAmount = operationTypeStrategy.calculatePercent(TEN_PERCENT, walletRequestBody.getAmount());

        account.setBalance(operationTypeStrategy.calculateAmount(account.getBalance(), newAmount));

        ExternalTransactionRequestBody externalTransaction = ExternalTransactionRequestBody.builder()
                .amount(newAmount)
                .userId(account.getUser().getId())
                .build();

        ExternalTransactionResponseBody externalTransactionResponseBody = new ExternalTransactionResponseBody();
        Transactions transactions;

        try {
            externalTransactionResponseBody = callPost(externalTransaction);
        } catch (HttpClientErrorException ex) {
            throw new ExternalException(TransactionMapper.getBuild(walletRequestBody.getAmount(), operationType, account,
                    TrasactionStatus.FAILED,
                    externalTransactionResponseBody, newAmount));
        }

        Account accountUpdated = accountRepository.save(account);

        transactions = TransactionMapper.getBuild(walletRequestBody.getAmount(), operationType, account,
                TrasactionStatus.COMPLETED, externalTransactionResponseBody, newAmount);

        transactionRepository.save(transactions);

        return WalletMapper.toTopUpResponseBody(account.getUser().getId(), walletRequestBody.getAmount(), account.getAccountNumber(),
                externalTransactionResponseBody == null ? null : externalTransactionResponseBody.getWalletId(), accountUpdated.getBalance(), operationType);
    }

    public ExternalTransactionBalanceResponseBody getBalance(Long userId) {

        ResponseEntity<ExternalTransactionBalanceResponseBody> response = restCaller
                .callGet(uriBalance + userId, new ParameterizedTypeReference<>() {
                });
        return response != null && response.hasBody() ? response.getBody() : new ExternalTransactionBalanceResponseBody();
    }

    public ExternalTransactionResponseBody callPost(ExternalTransactionRequestBody externalTransactionRequestBody) {

        ResponseEntity<ExternalTransactionResponseBody> response = restCaller
                .callPost(uriPayment, externalTransactionRequestBody, new ParameterizedTypeReference<>() {
                });

        return response != null && response.hasBody() ? response.getBody() : new ExternalTransactionResponseBody();
    }
}
