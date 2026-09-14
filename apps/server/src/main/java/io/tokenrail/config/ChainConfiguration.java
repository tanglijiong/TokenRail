package io.tokenrail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

@Configuration
public class ChainConfiguration {
    @Bean(destroyMethod = "shutdown")
    Web3j web3j(TokenRailProperties properties) {
        return Web3j.build(new HttpService(properties.rpcUrl()));
    }
}
