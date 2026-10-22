package com.linkr.backend.ticketmaster;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Attiva la configurazione Ticketmaster. Non crea nulla che parli con l'esterno: il client si costruisce senza aprire
 * connessioni e non esiste nessun ApplicationRunner, scheduler o listener di avvio in questo package (verificato da un test).
 */
@Configuration
@EnableConfigurationProperties(TicketmasterProperties.class)
public class TicketmasterConfig {
}
