package com.aztec.accountlookup.integration;

import com.aztec.accountlookup.AccountLookupServiceApplication;
import com.aztec.accountlookup.rest.api.AccountLookupResponse;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.http.HttpMethod.GET;

@SpringBootTest(classes=AccountLookupServiceApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWireMock(port=0)
@ActiveProfiles("test")
public class AccountLookupIntegrationTest {

    private static String IBAN = "12345";
    private static String COUNTRY = "UK";
    private static String CURRENCY = "GBP";

    private static final String BANK_ONE_NAME = "BANK ONE";
    private static final String BANK_ONE_ROUTING_NUMBER = "1111111111";

    private static final String BANK_TWO_NAME = "BANK TWO";
    private static final String BANK_TWO_ROUTING_NUMBER = "2222222222";

    @Autowired
    private CircuitBreakerRegistry registry;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    public void setup() {
        WireMock.reset();

        transitionToClosedState("lookupAccount");
    }

    @Test
    public void lookupUpAccount_BankOneSuccess_Test() {
        primeBankOneForSuccess(IBAN, COUNTRY, CURRENCY);

        ResponseEntity<AccountLookupResponse> response = callLookupAccount(IBAN, COUNTRY, CURRENCY);

        assertThat(response.getStatusCode(), equalTo(HttpStatus.OK));
        assertThat(response.getBody().getBankName(), equalTo(BANK_ONE_NAME));
        assertThat(response.getBody().getRoutingNumber(), equalTo(BANK_ONE_ROUTING_NUMBER));
    }

    /**
     * A 4xx such as a NOT FOUND (404) should not result in a failover to BANK TWO, as the client request needs to be
     * corrected.  So the exception is percolated up and returned to the client.
     */
    @Test
    public void lookupUpAccount_BanksOne_AccountNotFound_Test() {
        primeBankOneForFailure(404);
        // Still set bank two to success, to prove that this is not hit (as would return a 200).
        primeBankTwoForSuccess(IBAN, COUNTRY, CURRENCY);

        ResponseEntity<AccountLookupResponse> response = callLookupAccount(IBAN, COUNTRY, CURRENCY);

        assertThat(response.getStatusCode(), equalTo(HttpStatus.NOT_FOUND));
    }

    /**
     * A 5xx such as a SERVICE UNAVAILABLE (503) should result in a failover to BANK TWO.
     */
    @Test
    public void lookupUpAccount_BankOneUnavailable_FailoverToBankTwo_Test() {
        primeBankOneForFailure(503);
        primeBankTwoForSuccess(IBAN, COUNTRY, CURRENCY);

        ResponseEntity<AccountLookupResponse> response = callLookupAccount(IBAN, COUNTRY, CURRENCY);

        assertThat(response.getStatusCode(), equalTo(HttpStatus.OK));
        assertThat(response.getBody().getBankName(), equalTo(BANK_TWO_NAME));
        assertThat(response.getBody().getRoutingNumber(), equalTo(BANK_TWO_ROUTING_NUMBER));
    }

    /**
     * If the fallback call fails with a 5xx, then a 500 is returned to the client.
     */
    @Test
    public void lookupUpAccount_BankOneUnavailable_BankTwoBadGateway_Test() {
        primeBankOneForFailure(503);
        primeBankTwoForFailure(502);

        ResponseEntity<AccountLookupResponse> response = callLookupAccount(IBAN, COUNTRY, CURRENCY);

        assertThat(response.getStatusCode(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private ResponseEntity<AccountLookupResponse> callLookupAccount(String iban, String country, String currency) {
        HttpHeaders headerMap = new HttpHeaders();
        headerMap.add(HttpHeaders.AUTHORIZATION, "Bearer ${UUID.randomUUID().toString()}");
        ResponseEntity response = restTemplate.exchange("/v1/accountlookup/account?iban="+iban+"&country="+country+"&currency="+currency, GET, new HttpEntity<Object>(headerMap), AccountLookupResponse.class);
        return response;
    }

    private void transitionToClosedState(String circuitBreakerName) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(circuitBreakerName);
        if(circuitBreaker.getState() != CircuitBreaker.State.CLOSED) {
            circuitBreaker.transitionToClosedState();
        }
    }

    private static void primeBankOneForFailure(int responseCode) {
        stubFor(get(urlPathEqualTo("/bankone/api/account"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(responseCode)));
    }

    private static void primeBankTwoForFailure(int responseCode) {
        stubFor(get(urlPathEqualTo("/banktwo/api/account"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(responseCode)));
    }

    private static void primeBankOneForSuccess(String iban, String country, String currency) {
        stubFor(get(urlEqualTo("/bankone/api/account?iban="+iban+"&country="+country+"&currency="+currency))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("{\"bankName\": \""+ BANK_ONE_NAME +"\", \"iban\": \""+iban+"\", \"routingNumber\": \""+BANK_ONE_ROUTING_NUMBER+"\"}")));
    }

    private static void primeBankTwoForSuccess(String iban, String country, String currency) {
        stubFor(get(urlEqualTo("/banktwo/api/account?iban="+iban+"&country="+country+"&currency="+currency))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("{\"bankName\": \""+ BANK_TWO_NAME +"\", \"iban\": \""+iban+"\", \"routingNumber\": \""+BANK_TWO_ROUTING_NUMBER+"\"}")));
    }
}
