package com.aztec.accountlookup.router;

import com.aztec.accountlookup.exception.AccountNotFoundException;
import com.aztec.accountlookup.service.BankOneAccountLookupService;
import com.aztec.accountlookup.service.BankTwoAccountLookupService;
import com.aztec.accountlookup.rest.api.AccountLookupResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AccountLookupRouter {

    @Autowired
    private BankOneAccountLookupService bankOneService;

    @Autowired
    private BankTwoAccountLookupService bankTwoService;

    /**
     * Primary lookup with circuit breaker.
     */
    @CircuitBreaker(name = "lookupAccount", fallbackMethod = "lookupAccountFallback")
    public AccountLookupResponse lookupAccount(final String iban, final String country, final String currency) {
        return bankOneService.lookupAccount(iban, country, currency);
    }

    /**
     * Fallback method for a NOT FOUND exception, just percolate the exception back.
     *
     * Does not count towards opening the circuit.
     */
    private AccountLookupResponse lookupAccountFallback(final String iban, final String country, final String currency, final AccountNotFoundException e) {
        log.debug("Account lookup request resulted in a Not Found.");
        throw e;
    }

    /**
     * Fallback method for all other exception types.
     */
    private AccountLookupResponse lookupAccountFallback(final String iban, final String country, final String currency, final Throwable t) {
        log.error("Primary lookup request failed, failing over to Bank Two.  Error was: " + t.getMessage());
        log.debug("Fallback: routing account lookup request to Bank Two {}", iban);
        return bankTwoService.lookupAccount(iban, country, currency);
    }
}
